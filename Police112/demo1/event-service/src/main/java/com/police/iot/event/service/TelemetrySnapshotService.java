package com.police.iot.event.service;

import com.police.iot.common.dto.PoliceTelemetry;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.annotation.PostConstruct;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelemetrySnapshotService {

    private static final String REDIS_KEY_PREFIX = "twin:snapshot:";
    private static final String REDIS_DEVICE_INDEX_KEY = "twin:snapshot:devices";

    private final RedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    // Pre-registered counters for the hot path — avoids per-call MeterRegistry lookup
    private Map<StaleReason, Counter> staleCounters;

    @PostConstruct
    private void initCounters() {
        staleCounters = new EnumMap<>(StaleReason.class);
        for (StaleReason reason : StaleReason.values()) {
            staleCounters.put(reason, meterRegistry.counter("event.snapshot.stale.skipped", "reason", reason.metricTag));
        }
    }

    public record SnapshotStoreResult(boolean updated, Instant existingTimestamp) {}

    @CircuitBreaker(name = "redisReadOps", fallbackMethod = "getDeviceTelemetryFallback")
    @Retry(name = "redisReadOps", fallbackMethod = "getDeviceTelemetryFallback")
    @Bulkhead(name = "redisReadOps", fallbackMethod = "getDeviceTelemetryFallback")
    @RateLimiter(name = "redisReadOps", fallbackMethod = "getDeviceTelemetryFallback")
    public PoliceTelemetry getDeviceTelemetry(String deviceId) {
        Object telemetry = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + deviceId);
        return toTelemetry(telemetry);
    }

    @CircuitBreaker(name = "redisReadOps", fallbackMethod = "getAllTelemetryFallback")
    @Retry(name = "redisReadOps", fallbackMethod = "getAllTelemetryFallback")
    @Bulkhead(name = "redisReadOps", fallbackMethod = "getAllTelemetryFallback")
    @RateLimiter(name = "redisReadOps", fallbackMethod = "getAllTelemetryFallback")
    public List<PoliceTelemetry> getAllTelemetry() {
        Set<Object> deviceIds = redisTemplate.opsForSet().members(REDIS_DEVICE_INDEX_KEY);
        if (deviceIds == null || deviceIds.isEmpty()) {
            return List.of();
        }

        List<String> keys = deviceIds.stream()
                .map(id -> REDIS_KEY_PREFIX + id)
                .toList();

        // Single MGET round-trip replaces N individual GETs (N+1 fix)
        List<Object> rawValues = redisTemplate.opsForValue().multiGet(keys);

        List<PoliceTelemetry> result = new ArrayList<>(deviceIds.size());
        if (rawValues != null) {
            for (Object raw : rawValues) {
                PoliceTelemetry t = toTelemetry(raw);
                if (t != null) {
                    result.add(t);
                }
            }
        }
        return result;
    }

    @CircuitBreaker(name = "redisWriteOps", fallbackMethod = "storeTelemetryFallback")
    @Retry(name = "redisWriteOps", fallbackMethod = "storeTelemetryFallback")
    @Bulkhead(name = "redisWriteOps", fallbackMethod = "storeTelemetryFallback")
    public void storeTelemetry(PoliceTelemetry telemetry) {
        Observation.createNotStarted("event.redis.store", observationRegistry)
                .lowCardinalityKeyValue("redis.operation", "store")
                .observe(() -> {
                    redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + telemetry.getDeviceId(), telemetry);
                    redisTemplate.opsForSet().add(REDIS_DEVICE_INDEX_KEY, telemetry.getDeviceId());
                });
    }

    @CircuitBreaker(name = "redisWriteOps", fallbackMethod = "storeTelemetryIfNewerFallback")
    @Bulkhead(name = "redisWriteOps", fallbackMethod = "storeTelemetryIfNewerFallback")
    public SnapshotStoreResult storeTelemetryIfNewer(PoliceTelemetry telemetry) {
        String snapshotKey = REDIS_KEY_PREFIX + telemetry.getDeviceId();
        PoliceTelemetry current = toTelemetry(redisTemplate.opsForValue().get(snapshotKey));
        Instant currentTimestamp = current == null ? null : current.getTimestamp();
        Instant incomingTimestamp = telemetry.getTimestamp();

        StaleReason staleReason = staleReason(incomingTimestamp, currentTimestamp);
        if (staleReason != null) {
            staleCounters.get(staleReason).increment();
            return new SnapshotStoreResult(false, currentTimestamp);
        }

        redisTemplate.opsForValue().set(snapshotKey, telemetry);
        redisTemplate.opsForSet().add(REDIS_DEVICE_INDEX_KEY, telemetry.getDeviceId());
        return new SnapshotStoreResult(true, currentTimestamp);
    }

    public PoliceTelemetry getDeviceTelemetryFallback(String deviceId, Throwable throwable) {
        incrementFailure("get_device", throwable);
        log.warn("Returning fallback for device telemetry on {}", deviceId, throwable);
        return null;
    }

    public void storeTelemetryFallback(PoliceTelemetry telemetry, Throwable throwable) {
        incrementFailure("store", throwable);
        log.error("Unable to store telemetry snapshot for {}", telemetry.getDeviceId(), throwable);
    }

    public SnapshotStoreResult storeTelemetryIfNewerFallback(PoliceTelemetry telemetry, Throwable throwable) {
        incrementFailure("store_if_newer", throwable);
        log.error("Redis unavailable — dropping snapshot write for device {} (circuit open or bulkhead full): {}",
                telemetry.getDeviceId(), throwable.getMessage());
        // Return a non-updated result instead of re-throwing so the Kafka consumer commits the
        // offset and moves on. Re-throwing here causes DefaultErrorHandler to retry the message,
        // which multiplies load exactly when Redis is already under pressure.
        return new SnapshotStoreResult(false, null);
    }

    public List<PoliceTelemetry> getAllTelemetryFallback(Throwable throwable) {
        incrementFailure("get_all", throwable);
        log.warn("Returning empty telemetry list due to Redis failure", throwable);
        return List.of();
    }

    private void incrementFailure(String operation, Throwable throwable) {
        Counter.builder("event.redis.operation.failures")
                .description("Redis operation fallback count")
                .tag("operation", operation)
                .tag("exception", throwable.getClass().getSimpleName())
                .register(meterRegistry)
                .increment();
    }

    private PoliceTelemetry toTelemetry(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof PoliceTelemetry telemetry) {
            return telemetry;
        }
        try {
            return objectMapper.convertValue(value, PoliceTelemetry.class);
        } catch (IllegalArgumentException ex) {
            log.warn("Unable to convert Redis value to PoliceTelemetry: {}", value.getClass().getName());
            return null;
        }
    }

    private StaleReason staleReason(Instant incoming, Instant current) {
        if (current == null) {
            return null;
        }
        if (incoming == null) {
            return StaleReason.MISSING_INCOMING_TIMESTAMP;
        }
        if (incoming.equals(current)) {
            return StaleReason.EQUAL;
        }
        if (incoming.isBefore(current)) {
            return StaleReason.OLDER;
        }
        return null;
    }

    private enum StaleReason {
        OLDER("older"),
        EQUAL("equal"),
        MISSING_INCOMING_TIMESTAMP("missing_incoming_timestamp");

        private final String metricTag;

        StaleReason(String metricTag) {
            this.metricTag = metricTag;
        }
    }
}
