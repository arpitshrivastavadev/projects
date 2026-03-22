package com.police.iot.device.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class CorsFilterRegistrationConfig {

    @Bean
    public FilterRegistrationBean<DevCorsFilter> devCorsFilterRegistration(DevCorsFilter devCorsFilter) {
        FilterRegistrationBean<DevCorsFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(devCorsFilter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
