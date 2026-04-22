package com.police.iot.command;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@ComponentScan(basePackages = { "com.police.iot.command", "com.police.iot.common" })
@EntityScan(basePackages = { "com.police.iot.command.model" })
@EnableJpaRepositories(basePackages = { "com.police.iot.command.repository" })
@org.springframework.data.jpa.repository.config.EnableJpaAuditing(auditorAwareRef = "tenantAuditorAware")
public class CommandServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CommandServiceApplication.class, args);
    }
}
