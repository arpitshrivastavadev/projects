package com.police.iot.event.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.police.iot.common.dto.PoliceTelemetry;
import com.police.iot.event.service.TelemetrySnapshotService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class TelemetryConsumer {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    private final ObjectMapper objectMapper;
    private final TelemetrySnapshotService telemetrySnapshotService;
    private final MeterRegistry meterRegistry;

    @KafkaListener(
            topics = "${police.kafka.topics.telemetry:police-telemetry}",
            groupId = "${spring.kafka.consumer.group-id:police-event-group}",
            containerFactory = "telemetryMainKafkaListenerContainerFactory"
    )
    public void consumeMain(ConsumerRecord<String, String> record,
                            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        processRecord(record, topic);
    }

    @KafkaListener(
            topics = "${police.kafka.topics.telemetry-retry:police-telemetry-retry}",
            groupId = "${spring.kafka.consumer.group-id:police-event-group}",
            containerFactory = "telemetryRetryKafkaListenerContainerFactory"
    )
    public void consumeRetry(ConsumerRecord<String, String> record,
                             @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        processRecord(record, topic);
    }

    private void processRecord(ConsumerRecord<String, String> record, String topic) {
        String message = record.value();
        String correlationId = extractCorrelationId(record);
        try {
            PoliceTelemetry telemetry = objectMapper.readValue(message, PoliceTelemetry.class);
            log.info("Received telemetry for device={} topic={} partition={} offset={} correlationId={}",
                    telemetry.getDeviceId(), topic, record.partition(), record.offset(), correlationId);

            telemetrySnapshotService.storeTelemetry(telemetry);
            meterRegistry.counter("event.kafka.consume.success", "topic", topic).increment();

            if ("SOS".equalsIgnoreCase(telemetry.getStatus())) {
                log.warn("🚨 EMERGENCY: SOS received from Device: {} (Officer: {})",
                        telemetry.getDeviceId(), telemetry.getOfficerId());
            }

            if (telemetry.getSpeed() > 120) {
                log.info("⚠️ High Speed Alert: Device {} is moving at {} km/h",
                        telemetry.getDeviceId(), telemetry.getSpeed());
            }

        } catch (Exception e) {
            meterRegistry.counter("event.kafka.consume.failures", "topic", topic).increment();
            log.error("Failed to process telemetry message. topic={} partition={} offset={} correlationId={} payload={}",
                    topic, record.partition(), record.offset(), correlationId, message, e);
            throw new RuntimeException("Telemetry processing failed", e);
        }
    }

    private String extractCorrelationId(ConsumerRecord<String, String> record) {
        if (record.headers() == null) {
            return "n/a";
        }
        org.apache.kafka.common.header.Header header = record.headers().lastHeader(CORRELATION_ID_HEADER);
        if (header == null) {
            header = record.headers().lastHeader(CORRELATION_ID_HEADER.toLowerCase());
        }
        return header == null ? "n/a" : new String(header.value());
    }
}
