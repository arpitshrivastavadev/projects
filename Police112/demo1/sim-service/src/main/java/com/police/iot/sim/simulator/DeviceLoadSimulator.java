package com.police.iot.sim.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.police.iot.common.dto.PoliceTelemetry;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Random;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

@Service
@Slf4j
@RequiredArgsConstructor
public class DeviceLoadSimulator {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ThreadPoolExecutor simulatorExecutor;
    private final MeterRegistry meterRegistry;
    private final Random random = new Random();

    @Value("${police.simulator.device-count:10000}")
    private int deviceCount;

    @Scheduled(fixedRateString = "${police.simulator.interval-ms:2000}")
    public void simulateTelemetry() {
        log.info("Starting telemetry simulation for {} devices...", deviceCount);
        for (int i = 0; i < deviceCount; i++) {
            final int deviceIndex = i;
            try {
                simulatorExecutor.execute(() -> sendTelemetry(deviceIndex));
            } catch (RejectedExecutionException ex) {
                Counter.builder("simulator.tasks.rejected")
                        .description("Rejected telemetry send tasks")
                        .tag("reason", "executor_saturated")
                        .register(meterRegistry)
                        .increment();
                log.warn("Dropping telemetry for device index {} due to saturated executor", deviceIndex);
            }
        }
    }

    private void sendTelemetry(int index) {
        String deviceId = "POLICE-DEV-" + index;
        String tenantId = (index % 3 == 0) ? "NYPD" : (index % 3 == 1) ? "LAPD" : "CHPD";

        PoliceTelemetry telemetry = PoliceTelemetry.builder()
                .deviceId(deviceId)
                .tenantId(tenantId)
                .timestamp(Instant.now())
                .latitude(40.7128 + (random.nextDouble() - 0.5) * 0.1)
                .longitude(-74.0060 + (random.nextDouble() - 0.5) * 0.1)
                .speed(random.nextDouble() * 140)
                .batteryLevel(random.nextInt(100))
                .status(random.nextInt(100) > 98 ? "SOS" : "ACTIVE")
                .vehicleId("VEH-" + index)
                .officerId("OFF-" + index)
                .build();

        try {
            String payload = objectMapper.writeValueAsString(telemetry);
            publishTelemetry(deviceId, payload);
        } catch (Exception e) {
            log.error("Failed to prepare telemetry for device {}", deviceId, e);
        }
    }

    @CircuitBreaker(name = "kafkaPublisher", fallbackMethod = "publishFallback")
    @Retry(name = "kafkaPublisher", fallbackMethod = "publishFallback")
    @RateLimiter(name = "kafkaPublisher", fallbackMethod = "publishFallback")
    @Bulkhead(name = "kafkaPublisher", fallbackMethod = "publishFallback")
    public void publishTelemetry(String deviceId, String payload) {
        kafkaTemplate.send("police-telemetry", deviceId, payload);
    }

    private void publishFallback(String deviceId, String payload, Throwable throwable) {
        Counter.builder("simulator.publish.failures")
                .description("Failed telemetry publish attempts")
                .tag("exception", throwable.getClass().getSimpleName())
                .register(meterRegistry)
                .increment();
        log.error("Failed to publish telemetry for device {} after resilience policies", deviceId, throwable);
    }
}
