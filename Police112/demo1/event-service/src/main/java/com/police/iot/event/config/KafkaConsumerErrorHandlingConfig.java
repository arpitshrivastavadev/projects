package com.police.iot.event.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class KafkaConsumerErrorHandlingConfig {

    private final ConsumerFactory<String, String> consumerFactory;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${police.kafka.topics.telemetry:police-telemetry}")
    private String telemetryTopic;

    @Value("${police.kafka.topics.telemetry-retry:police-telemetry-retry}")
    private String telemetryRetryTopic;

    @Value("${police.kafka.topics.telemetry-dlq:police-telemetry-dlq}")
    private String telemetryDlqTopic;

    @Value("${police.kafka.retry.main.backoff-ms:1000}")
    private long mainBackoffMs;

    @Value("${police.kafka.retry.main.attempts:2}")
    private long mainAttempts;

    @Value("${police.kafka.retry.retry-topic.backoff-ms:2000}")
    private long retryTopicBackoffMs;

    @Value("${police.kafka.retry.retry-topic.attempts:2}")
    private long retryTopicAttempts;

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> telemetryMainKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = baseFactory();
        factory.setCommonErrorHandler(mainTopicErrorHandler());
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> telemetryRetryKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = baseFactory();
        factory.setCommonErrorHandler(retryTopicErrorHandler());
        return factory;
    }

    @Bean
    public DefaultErrorHandler mainTopicErrorHandler() {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> {
                    logRouting(record, ex, telemetryRetryTopic, "main_to_retry");
                    return new TopicPartition(telemetryRetryTopic, record.partition());
                }
        );

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(mainBackoffMs, mainAttempts));
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) -> meterRegistry.counter(
                "event.kafka.consume.retry.attempts",
                "topic", telemetryTopic,
                "attempt", String.valueOf(deliveryAttempt)
        ).increment());
        return errorHandler;
    }

    @Bean
    public DefaultErrorHandler retryTopicErrorHandler() {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> {
                    logRouting(record, ex, telemetryDlqTopic, "retry_to_dlq");
                    return new TopicPartition(telemetryDlqTopic, record.partition());
                }
        );

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(retryTopicBackoffMs, retryTopicAttempts));
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) -> meterRegistry.counter(
                "event.kafka.consume.retry.attempts",
                "topic", telemetryRetryTopic,
                "attempt", String.valueOf(deliveryAttempt)
        ).increment());
        return errorHandler;
    }

    private ConcurrentKafkaListenerContainerFactory<String, String> baseFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    private void logRouting(ConsumerRecord<?, ?> record, Exception ex, String destinationTopic, String path) {
        meterRegistry.counter("event.kafka.consume.failures.routed", "path", path, "destination", destinationTopic).increment();
        log.error("Routing telemetry message after retries exhausted: sourceTopic={}, destinationTopic={}, partition={}, offset={}, key={}, exceptionType={}, message={}",
                record.topic(), destinationTopic, record.partition(), record.offset(), record.key(), ex.getClass().getSimpleName(), ex.getMessage());
    }
}
