package com.police.iot.event.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.police.iot.common.dto.PoliceTelemetry;
import com.police.iot.event.service.TelemetrySnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class TelemetryConsumer {

    private final ObjectMapper objectMapper;
    private final TelemetrySnapshotService telemetrySnapshotService;

    @KafkaListener(topics = "police-telemetry", groupId = "police-event-group")
    public void consume(@Payload String message) {
        try {
            PoliceTelemetry telemetry = objectMapper.readValue(message, PoliceTelemetry.class);
            log.debug("Received telemetry for device: {}", telemetry.getDeviceId());

            telemetrySnapshotService.storeTelemetry(telemetry);

            if ("SOS".equalsIgnoreCase(telemetry.getStatus())) {
                log.warn("🚨 EMERGENCY: SOS received from Device: {} (Officer: {})",
                        telemetry.getDeviceId(), telemetry.getOfficerId());
            }

            if (telemetry.getSpeed() > 120) {
                log.info("⚠️ High Speed Alert: Device {} is moving at {} km/h",
                        telemetry.getDeviceId(), telemetry.getSpeed());
            }

        } catch (Exception e) {
            log.error("Failed to process telemetry message: {}", message, e);
        }
    }
}
