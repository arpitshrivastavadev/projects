package com.police.iot.sim.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class SimulatorExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor simulatorExecutor(
            @Value("${police.simulator.executor.core-size:8}") int coreSize,
            @Value("${police.simulator.executor.max-size:16}") int maxSize,
            @Value("${police.simulator.executor.queue-capacity:1000}") int queueCapacity) {

        return new ThreadPoolExecutor(
                coreSize,
                maxSize,
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
