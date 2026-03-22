package com.police.iot.event.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.police.iot.common.dto.PoliceTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelemetrySnapshotServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private SetOperations<String, Object> setOperations;

    @Test
    void getAllTelemetryFallbackReturnsEmptyList() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ObjectMapper objectMapper = new ObjectMapper();
        TelemetrySnapshotService service = new TelemetrySnapshotService(redisTemplate, meterRegistry, objectMapper);

        List<?> result = service.getAllTelemetryFallback(new RuntimeException("redis-down"));

        assertEquals(0, result.size());
        assertEquals(1.0, meterRegistry.get("event.redis.operation.failures").counter().count());
    }

    @Test
    void storeTelemetryAddsSnapshotAndDeviceIdToIndex() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TelemetrySnapshotService service = new TelemetrySnapshotService(redisTemplate, meterRegistry, new ObjectMapper());
        PoliceTelemetry telemetry = PoliceTelemetry.builder().deviceId("device-1").build();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        service.storeTelemetry(telemetry);

        verify(valueOperations).set("twin:snapshot:device-1", telemetry);
        verify(setOperations).add("twin:snapshot:devices", "device-1");
    }

    @Test
    void getAllTelemetryReadsFromIndexedDeviceIds() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TelemetrySnapshotService service = new TelemetrySnapshotService(redisTemplate, meterRegistry, new ObjectMapper());
        PoliceTelemetry telemetry = PoliceTelemetry.builder().deviceId("device-1").officerId("officer-1").build();

        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(setOperations.members("twin:snapshot:devices")).thenReturn(Set.of("device-1", "device-2"));
        when(valueOperations.get("twin:snapshot:device-1")).thenReturn(telemetry);
        when(valueOperations.get("twin:snapshot:device-2")).thenReturn(null);

        List<PoliceTelemetry> result = service.getAllTelemetry();

        assertEquals(1, result.size());
        assertEquals("device-1", result.get(0).getDeviceId());
    }

    @Test
    void getDeviceTelemetryConvertsMappedRedisValue() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TelemetrySnapshotService service = new TelemetrySnapshotService(redisTemplate, meterRegistry, new ObjectMapper());

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("twin:snapshot:device-1"))
                .thenReturn(java.util.Map.of("deviceId", "device-1", "officerId", "officer-1"));

        PoliceTelemetry result = service.getDeviceTelemetry("device-1");

        assertEquals("device-1", result.getDeviceId());
        assertEquals("officer-1", result.getOfficerId());
    }

    @Test
    void getDeviceTelemetryReturnsNullWhenRedisValueCannotBeConverted() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TelemetrySnapshotService service = new TelemetrySnapshotService(redisTemplate, meterRegistry, new ObjectMapper());

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("twin:snapshot:device-1")).thenReturn(List.of("unexpected"));

        PoliceTelemetry result = service.getDeviceTelemetry("device-1");

        assertNull(result);
    }
}
