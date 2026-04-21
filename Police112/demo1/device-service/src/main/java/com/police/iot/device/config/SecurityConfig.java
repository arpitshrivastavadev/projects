package com.police.iot.device.config;

import com.police.iot.common.security.TenantFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

import java.util.Optional;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final TenantFilter tenantFilter;
    private final Optional<DevJwtAuthenticationFilter> devJwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/dashboard.html", "/index.html", "/error", "/favicon.ico").permitAll()
                        .requestMatchers("/actuator/**", "/livez", "/readyz").permitAll()
                        .requestMatchers("/api/v1/police/**").authenticated()
                        .anyRequest().authenticated())
                .addFilterAfter(tenantFilter, BasicAuthenticationFilter.class);

        devJwtAuthenticationFilter.ifPresent(filter -> http.addFilterBefore(filter, BasicAuthenticationFilter.class));

        return http.build();
    }
}
