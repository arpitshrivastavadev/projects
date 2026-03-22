package com.police.iot.sim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(exclude = { DataSourceAutoConfiguration.class })
@ComponentScan(basePackages = { "com.police.iot.sim", "com.police.iot.common" })
@EnableScheduling
public class SimServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SimServiceApplication.class, args);
    }
}
