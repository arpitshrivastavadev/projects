package com.police.iot.event.service;

import com.police.iot.common.dto.PoliceTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelemetryEventIdempotencyServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Test
    void buildIdempotencyKeyUsesEventIdWhenPresent() {
        TelemetryEventIdempotencyService service = new TelemetryEventIdempotencyService(
                redisTemplate,
                new SimpleMeterRegistry(),
                Duration.ofHours(24)
        );

        PoliceTelemetry telemetry = PoliceTelemetry.builder()
                .tenantId("NYPD")
                .deviceId("device-1")
                .eventId("evt-123")
                .timestamp(Instant.parse("2026-04-21T10:15:30Z"))
                .build();

        String key = service.buildIdempotencyKey(telemetry);

        assertEquals("event:idempotency:NYPD:device-1:event:evt-123", key);
    }

    @Test
    void buildIdempotencyKeyFallsBackToTenantDeviceTimestamp() {
        TelemetryEventIdempotencyService service = new TelemetryEventIdempotencyService(
                redisTemplate,
                new SimpleMeterRegistry(),
                Duration.ofHours(24)
        );

        PoliceTelemetry telemetry = PoliceTelemetry.builder()
                .tenantId("NYPD")
                .deviceId("device-1")
                .timestamp(Instant.parse("2026-04-21T10:15:30Z"))
                .build();

        String key = service.buildIdempotencyKey(telemetry);

        assertEquals("event:idempotency:NYPD:device-1:ts:2026-04-21T10:15:30Z", key);
    }

    @Test
    void claimUsesSetIfAbsentWithTtl() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TelemetryEventIdempotencyService service = new TelemetryEventIdempotencyService(
                redisTemplate,
                meterRegistry,
                Duration.ofMinutes(30)
        );

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent("event:idempotency:NYPD:device-1:event:evt-1", "1", Duration.ofMinutes(30)))
                .thenReturn(true);

        boolean claimed = service.claim("event:idempotency:NYPD:device-1:event:evt-1");

        assertTrue(claimed);
        verify(valueOperations).setIfAbsent("event:idempotency:NYPD:device-1:event:evt-1", "1", Duration.ofMinutes(30));
        assertEquals(1.0, meterRegistry.get("event.idempotency.claim").tag("result", "claimed").counter().count());
    }
}
