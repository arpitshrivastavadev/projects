package com.police.iot.common.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic telemetryTopic() {
        return TopicBuilder.name("police-telemetry")
                .partitions(50)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic telemetryRetryTopic() {
        return TopicBuilder.name("police-telemetry-retry")
                .partitions(50)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic telemetryDlqTopic() {
        return TopicBuilder.name("police-telemetry-dlq")
                .partitions(50)
                .replicas(1)
                .build();
    }
}
