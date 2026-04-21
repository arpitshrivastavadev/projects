package com.police.iot.event.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.police.iot.common.dto.PoliceTelemetry;
import com.police.iot.event.service.TelemetryEventIdempotencyService;
import com.police.iot.event.service.TelemetrySnapshotService;
import com.police.iot.event.service.TelemetrySnapshotService.SnapshotStoreResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class TelemetryConsumerIdempotencyTest {

    @Mock
    private TelemetrySnapshotService telemetrySnapshotService;

    @Mock
    private TelemetryEventIdempotencyService idempotencyService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void firstEventIsProcessedNormally() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TelemetryConsumer consumer = new TelemetryConsumer(objectMapper, telemetrySnapshotService, idempotencyService, meterRegistry);
        ConsumerRecord<String, String> record = createRecord("device-1", "corr-1");

        when(idempotencyService.buildIdempotencyKey(any(PoliceTelemetry.class))).thenReturn("event:idempotency:NYPD:device-1:ts:2026-04-21T10:15:30Z");
        when(idempotencyService.claim("event:idempotency:NYPD:device-1:ts:2026-04-21T10:15:30Z")).thenReturn(true);
        when(telemetrySnapshotService.storeTelemetryIfNewer(any(PoliceTelemetry.class)))
                .thenReturn(new SnapshotStoreResult(true, null));

        consumer.consumeMain(record, "police-telemetry");

        verify(telemetrySnapshotService).storeTelemetryIfNewer(any(PoliceTelemetry.class));
        verify(idempotencyService, never()).release(any());
    }

    @Test
    void duplicateEventIsSkippedAndNotReprocessed() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TelemetryConsumer consumer = new TelemetryConsumer(objectMapper, telemetrySnapshotService, idempotencyService, meterRegistry);
        ConsumerRecord<String, String> record = createRecord("device-1", "corr-2");

        when(idempotencyService.buildIdempotencyKey(any(PoliceTelemetry.class))).thenReturn("event:idempotency:NYPD:device-1:ts:2026-04-21T10:15:30Z");
        when(idempotencyService.claim("event:idempotency:NYPD:device-1:ts:2026-04-21T10:15:30Z")).thenReturn(false);

        consumer.consumeMain(record, "police-telemetry");

        verify(telemetrySnapshotService, never()).storeTelemetryIfNewer(any(PoliceTelemetry.class));
        verify(idempotencyService, never()).release(any());
    }

    @Test
    void markerIsClearedWhenProcessingFailsAfterClaim() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TelemetryConsumer consumer = new TelemetryConsumer(objectMapper, telemetrySnapshotService, idempotencyService, meterRegistry);
        ConsumerRecord<String, String> record = createRecord("device-1", "corr-3");

        when(idempotencyService.buildIdempotencyKey(any(PoliceTelemetry.class))).thenReturn("event:idempotency:NYPD:device-1:ts:2026-04-21T10:15:30Z");
        when(idempotencyService.claim("event:idempotency:NYPD:device-1:ts:2026-04-21T10:15:30Z")).thenReturn(true);
        doThrow(new RuntimeException("redis write failure")).when(telemetrySnapshotService).storeTelemetryIfNewer(any(PoliceTelemetry.class));

        assertThrows(RuntimeException.class, () -> consumer.consumeMain(record, "police-telemetry"));

        verify(idempotencyService).release("event:idempotency:NYPD:device-1:ts:2026-04-21T10:15:30Z");
    }

    @Test
    void staleEventIsSkippedAfterClaimWithoutReleasingIdempotencyMarker() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TelemetryConsumer consumer = new TelemetryConsumer(objectMapper, telemetrySnapshotService, idempotencyService, meterRegistry);
        ConsumerRecord<String, String> record = createRecord("device-1", "corr-4");

        when(idempotencyService.buildIdempotencyKey(any(PoliceTelemetry.class))).thenReturn("event:idempotency:NYPD:device-1:event:evt-4");
        when(idempotencyService.claim("event:idempotency:NYPD:device-1:event:evt-4")).thenReturn(true);
        when(telemetrySnapshotService.storeTelemetryIfNewer(any(PoliceTelemetry.class)))
                .thenReturn(new SnapshotStoreResult(false, Instant.parse("2026-04-21T10:16:30Z")));

        consumer.consumeMain(record, "police-telemetry");

        verify(telemetrySnapshotService).storeTelemetryIfNewer(any(PoliceTelemetry.class));
        verify(idempotencyService, never()).release(any());
    }

    private ConsumerRecord<String, String> createRecord(String deviceId, String correlationId) throws Exception {
        PoliceTelemetry telemetry = PoliceTelemetry.builder()
                .deviceId(deviceId)
                .tenantId("NYPD")
                .timestamp(Instant.parse("2026-04-21T10:15:30Z"))
                .latitude(40.1)
                .longitude(-74.1)
                .speed(60.0)
                .batteryLevel(90)
                .officerId("OFF-1")
                .vehicleId("VEH-1")
                .status("ACTIVE")
                .build();

        ConsumerRecord<String, String> record = new ConsumerRecord<>("police-telemetry", 0, 1L, deviceId,
                objectMapper.writeValueAsString(telemetry));
        RecordHeaders headers = new RecordHeaders();
        headers.add("X-Correlation-ID", correlationId.getBytes(StandardCharsets.UTF_8));
        headers.forEach(record.headers()::add);
        return record;
    }
}
