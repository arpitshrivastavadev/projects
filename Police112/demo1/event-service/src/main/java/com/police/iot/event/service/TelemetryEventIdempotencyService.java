package com.police.iot.event.service;

import com.police.iot.common.dto.PoliceTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
@Slf4j
public class TelemetryEventIdempotencyService {

    private static final String IDEMPOTENCY_KEY_PREFIX = "event:idempotency:";
    private static final String MARKER_VALUE = "1";

    private final RedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;
    private final Duration markerTtl;

    public TelemetryEventIdempotencyService(RedisTemplate<String, Object> redisTemplate,
                                            MeterRegistry meterRegistry,
                                            @Value("${police.event.idempotency.ttl:PT24H}") Duration markerTtl) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.markerTtl = markerTtl;
    }

    public String buildIdempotencyKey(PoliceTelemetry telemetry) {
        String tenantId = normalize(telemetry.getTenantId());
        String deviceId = normalize(telemetry.getDeviceId());

        if (hasText(telemetry.getEventId())) {
            return IDEMPOTENCY_KEY_PREFIX + tenantId + ':' + deviceId + ":event:" + telemetry.getEventId();
        }

        Instant timestamp = telemetry.getTimestamp();
        String timestampPart = timestamp == null ? "null-ts" : timestamp.toString();
        return IDEMPOTENCY_KEY_PREFIX + tenantId + ':' + deviceId + ":ts:" + timestampPart;
    }

    public boolean claim(String idempotencyKey) {
        Boolean claimed = redisTemplate.opsForValue().setIfAbsent(idempotencyKey, MARKER_VALUE, markerTtl);
        boolean result = Boolean.TRUE.equals(claimed);
        meterRegistry.counter("event.idempotency.claim", "result", result ? "claimed" : "duplicate").increment();
        return result;
    }

    public void release(String idempotencyKey) {
        Boolean deleted = redisTemplate.delete(idempotencyKey);
        if (Boolean.TRUE.equals(deleted)) {
            meterRegistry.counter("event.idempotency.release", "result", "deleted").increment();
            return;
        }
        meterRegistry.counter("event.idempotency.release", "result", "not_found").increment();
    }

    private static String normalize(String input) {
        return hasText(input) ? input : "unknown";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
