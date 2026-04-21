package com.police.iot.event.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.police.iot.common.dto.PoliceTelemetry;
import com.police.iot.event.EventServiceApplication;
import com.police.iot.event.service.TelemetryEventIdempotencyService;
import com.police.iot.event.service.TelemetrySnapshotService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(
        classes = EventServiceApplication.class,
        properties = {
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "spring.kafka.consumer.auto-offset-reset=earliest",
                "spring.kafka.consumer.group-id=test-event-group",
                "spring.kafka.consumer.enable-auto-commit=false",
                "police.kafka.retry.main.attempts=0",
                "police.kafka.retry.main.backoff-ms=10",
                "police.kafka.retry.retry-topic.attempts=1",
                "police.kafka.retry.retry-topic.backoff-ms=10"
        }
)
@EmbeddedKafka(partitions = 1, topics = {
        "police-telemetry",
        "police-telemetry-retry",
        "police-telemetry-dlq"
})
class TelemetryConsumerRetryDlqIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @MockBean
    private TelemetrySnapshotService telemetrySnapshotService;

    @MockBean
    private TelemetryEventIdempotencyService idempotencyService;

    @AfterEach
    void tearDown() {
        clearInvocations(telemetrySnapshotService);
    }

    @Test
    void transientFailureIsRecoveredViaRetryTopic() throws Exception {
        when(idempotencyService.buildIdempotencyKey(any(PoliceTelemetry.class))).thenReturn("test-key");
        when(idempotencyService.claim("test-key")).thenReturn(true);

        Map<String, Integer> attemptsByDevice = new ConcurrentHashMap<>();
        doAnswer(invocation -> {
            PoliceTelemetry telemetry = invocation.getArgument(0);
            if ("transient-device".equals(telemetry.getDeviceId())) {
                int attempts = attemptsByDevice.merge(telemetry.getDeviceId(), 1, Integer::sum);
                if (attempts == 1) {
                    throw new RuntimeException("temporary redis error");
                }
            }
            return null;
        }).when(telemetrySnapshotService).storeTelemetry(any(PoliceTelemetry.class));

        sendTelemetry("transient-device", "corr-transient");

        verify(telemetrySnapshotService, times(2)).storeTelemetry(any(PoliceTelemetry.class));
        assertEquals(2, attemptsByDevice.get("transient-device"));
    }

    @Test
    void poisonMessageIsRoutedToDlqAfterRetryExhaustion() throws Exception {
        when(idempotencyService.buildIdempotencyKey(any(PoliceTelemetry.class))).thenReturn("test-key");
        when(idempotencyService.claim("test-key")).thenReturn(true);
        doThrow(new RuntimeException("always failing")).when(telemetrySnapshotService).storeTelemetry(any(PoliceTelemetry.class));

        sendTelemetry("poison-device", "corr-poison");

        verify(telemetrySnapshotService, times(3)).storeTelemetry(any(PoliceTelemetry.class));

        ConsumerRecord<String, String> dlqRecord = pollSingleRecord("police-telemetry-dlq", 10);
        assertNotNull(dlqRecord);
        assertTrue(dlqRecord.value().contains("poison-device"));

        org.apache.kafka.common.header.Header correlationHeader = dlqRecord.headers().lastHeader("X-Correlation-ID");
        assertNotNull(correlationHeader);
        assertEquals("corr-poison", new String(correlationHeader.value()));
    }

    private void sendTelemetry(String deviceId, String correlationId) throws Exception {
        PoliceTelemetry telemetry = PoliceTelemetry.builder()
                .deviceId(deviceId)
                .tenantId("NYPD")
                .timestamp(Instant.now())
                .latitude(40.1)
                .longitude(-74.1)
                .speed(45.0)
                .batteryLevel(90)
                .officerId("OFF-1")
                .vehicleId("VEH-1")
                .status("ACTIVE")
                .build();

        String payload = objectMapper.writeValueAsString(telemetry);
        Message<String> message = MessageBuilder.withPayload(payload)
                .setHeader(KafkaHeaders.TOPIC, "police-telemetry")
                .setHeader(KafkaHeaders.MESSAGE_KEY, deviceId)
                .setHeader("X-Correlation-ID", correlationId)
                .build();

        kafkaTemplate.send(message).get(5, TimeUnit.SECONDS);
    }

    private ConsumerRecord<String, String> pollSingleRecord(String topic, long timeoutSeconds) {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("dlq-test-group", "true", embeddedKafkaBroker);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Consumer<String, String> consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<>(consumerProps, new StringDeserializer(), new StringDeserializer());

        try (consumer) {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, topic);
            return KafkaTestUtils.getSingleRecord(consumer, topic, timeoutSeconds * 1_000L);
        }
    }
}
