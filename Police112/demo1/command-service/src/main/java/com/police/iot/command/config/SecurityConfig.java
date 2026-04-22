package com.police.iot.command.config;

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

    private final CommandTenantFilter commandTenantFilter;
    private final Optional<DevJwtAuthenticationFilter> devJwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**", "/livez", "/readyz").permitAll()
                        .requestMatchers("/api/v1/commands/**").authenticated()
                        .anyRequest().authenticated())
                .addFilterAfter(commandTenantFilter, BasicAuthenticationFilter.class);

        devJwtAuthenticationFilter.ifPresent(filter -> http.addFilterBefore(filter, BasicAuthenticationFilter.class));

        return http.build();
    }
}
