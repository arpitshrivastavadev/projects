package com.police.iot.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Logs every inbound HTTP request and its outbound response.
 *
 * What is logged (as structured JSON fields via SLF4J MDC + logback):
 *   - method, uri, queryString, remoteIp
 *   - responseStatus, durationMs, responseSize (Content-Length)
 *
 * What is NOT logged:
 *   - Request/response body  → too expensive at 3K RPS; use request-scoped tracing instead
 *   - Authorization header   → security risk
 *
 * Prod usage: these lines appear in Kibana under index "police-ecp-*"
 * with fields method, uri, status, duration_ms — alertable on 5xx and slow requests.
 *
 * Interview talking point:
 *   "I log method + URI + status + latency on every request. At 3K RPS this is ~260K
 *    log lines/min, so we send them async to Logstash and use sampling in high-volume
 *    endpoints like /telemetry/all."
 */
@Slf4j
@Component
@Order(2)   // runs after CorrelationIdFilter (Order 1) so correlationId is already in MDC
public class RequestResponseLoggingFilter extends OncePerRequestFilter {

    // Skip actuator health/prometheus polls — they'd drown real traffic logs
    private static final String[] SKIP_PATHS = {
        "/actuator/health", "/actuator/prometheus", "/actuator/info"
    };

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        for (String skip : SKIP_PATHS) {
            if (uri.startsWith(skip)) return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        long startMs = System.currentTimeMillis();

        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = System.currentTimeMillis() - startMs;
            int  status     = response.getStatus();

            // Structured log — Kibana can filter/alert on each field independently
            if (status >= 500) {
                log.error("HTTP request completed method={} uri={} query={} status={} duration_ms={} client_ip={}",
                        request.getMethod(),
                        request.getRequestURI(),
                        request.getQueryString(),
                        status,
                        durationMs,
                        request.getRemoteAddr());
            } else if (status >= 400) {
                log.warn("HTTP request completed method={} uri={} query={} status={} duration_ms={} client_ip={}",
                        request.getMethod(),
                        request.getRequestURI(),
                        request.getQueryString(),
                        status,
                        durationMs,
                        request.getRemoteAddr());
            } else {
                log.info("HTTP request completed method={} uri={} status={} duration_ms={}",
                        request.getMethod(),
                        request.getRequestURI(),
                        status,
                        durationMs);
            }

            // Alert-worthy: log slow requests separately so Kibana/Grafana can count them
            if (durationMs > 1000) {
                log.warn("SLOW REQUEST detected method={} uri={} duration_ms={} threshold_ms=1000",
                        request.getMethod(), request.getRequestURI(), durationMs);
            }
        }
    }
}
