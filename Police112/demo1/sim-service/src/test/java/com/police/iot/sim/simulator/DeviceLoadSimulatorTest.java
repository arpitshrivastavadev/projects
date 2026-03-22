package com.police.iot.sim.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;

class DeviceLoadSimulatorTest {

    @Test
    void simulateTelemetryHandlesRejectionsWithoutThrowing() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                1,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy());

        DeviceLoadSimulator simulator = new DeviceLoadSimulator(kafkaTemplate, objectMapper, executor, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(simulator, "deviceCount", 100);
        simulator.simulateTelemetry();
        executor.shutdownNow();
    }
}
