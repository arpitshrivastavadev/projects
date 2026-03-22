package com.police.iot.device;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@ComponentScan(basePackages = { "com.police.iot.device", "com.police.iot.common" })
@EntityScan(basePackages = { "com.police.iot.common.model", "com.police.iot.device.model" })
@EnableJpaRepositories(basePackages = { "com.police.iot.device.repository" })
@org.springframework.data.jpa.repository.config.EnableJpaAuditing(auditorAwareRef = "tenantAuditorAware")
public class DeviceServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeviceServiceApplication.class, args);
    }
}
