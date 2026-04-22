package com.police.iot.device.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.police.iot.common.dto.PoliceTelemetry;
import com.police.iot.common.security.CorrelationIdFilter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelemetryPublishService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${police.kafka.topics.telemetry:police-telemetry}")
    private String telemetryTopic;

    @PostConstruct
    void enableKafkaObservation() {
        kafkaTemplate.setObservationEnabled(true);
    }

    public void publish(PoliceTelemetry telemetry) {
        try {
            String payload = objectMapper.writeValueAsString(telemetry);
            String correlationId = MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID);

            var messageBuilder = MessageBuilder.withPayload(payload)
                    .setHeader(KafkaHeaders.TOPIC, telemetryTopic)
                    .setHeader(KafkaHeaders.KEY, telemetry.getDeviceId());

            if (correlationId != null && !correlationId.isBlank()) {
                messageBuilder.setHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, correlationId);
            }

            kafkaTemplate.send(messageBuilder.build());
            log.info("Published telemetry event for deviceId={} topic={} correlationId={}",
                    telemetry.getDeviceId(), telemetryTopic, correlationId);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize telemetry payload", e);
        }
    }
}
