package com.police.iot.device.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.police.iot.common.security.TenantContext;
import com.police.iot.device.model.IdempotencyRecord;
import com.police.iot.device.repository.IdempotencyRecordRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyAspect {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;

    @Around("@annotation(com.police.iot.device.idempotency.IdempotentWrite)")
    @Transactional
    public Object enforceIdempotency(ProceedingJoinPoint joinPoint) throws Throwable {
        String tenantId = TenantContext.getTenantId();
        String idempotencyKey = request.getHeader(IDEMPOTENCY_HEADER);
        String route = request.getRequestURI();
        String method = request.getMethod();

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException("Idempotency-Key header is required for this endpoint");
        }

        Object payload = firstPayloadArg(joinPoint.getArgs());
        String requestHash = hashPayload(payload);

        Optional<IdempotencyRecord> existing = idempotencyRecordRepository
                .findByTenantIdAndRouteAndHttpMethodAndIdempotencyKey(tenantId, route, method, idempotencyKey);

        if (existing.isPresent()) {
            return replayOrReject(existing.get(), requestHash, joinPoint);
        }

        IdempotencyRecord record = new IdempotencyRecord();
        record.setTenantId(tenantId);
        record.setRoute(route);
        record.setHttpMethod(method);
        record.setIdempotencyKey(idempotencyKey);
        record.setRequestHash(requestHash);

        try {
            idempotencyRecordRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException ex) {
            IdempotencyRecord concurrentRecord = idempotencyRecordRepository
                    .findByTenantIdAndRouteAndHttpMethodAndIdempotencyKey(tenantId, route, method, idempotencyKey)
                    .orElseThrow(() -> ex);
            return replayOrReject(concurrentRecord, requestHash, joinPoint);
        }

        Object result = joinPoint.proceed();
        record.setResponseStatus(response.getStatus());
        record.setResponseBody(writeJson(result));
        idempotencyRecordRepository.save(record);
        return result;
    }

    private Object replayOrReject(IdempotencyRecord record, String requestHash, ProceedingJoinPoint joinPoint) throws Throwable {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException("Idempotency-Key reuse with different payload is not allowed");
        }

        if (record.getResponseBody() == null || record.getResponseStatus() == null) {
            throw new IdempotencyConflictException("Request with this Idempotency-Key is already in progress");
        }

        response.setStatus(record.getResponseStatus());
        Method method = getMethod(joinPoint);
        Class<?> returnType = method.getReturnType();
        return objectMapper.readValue(record.getResponseBody(), returnType);
    }

    private Method getMethod(ProceedingJoinPoint joinPoint) {
        Method method = ((org.aspectj.lang.reflect.MethodSignature) joinPoint.getSignature()).getMethod();
        return method;
    }

    private Object firstPayloadArg(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        return args[0];
    }

    private String hashPayload(Object payload) {
        try {
            String payloadJson = writeJson(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(payloadJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize payload", e);
        }
    }
}
