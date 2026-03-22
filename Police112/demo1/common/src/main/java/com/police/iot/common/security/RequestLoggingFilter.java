package com.police.iot.common.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Iterator;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1) // Run after CorrelationIdFilter
@Slf4j
public class RequestLoggingFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        long startTime = System.currentTimeMillis();

        if (log.isDebugEnabled()) {
            StringBuilder params = new StringBuilder();
            Enumeration<String> parameterNames = httpRequest.getParameterNames();
            while (parameterNames.hasMoreElements()) {
                String paramName = parameterNames.nextElement();
                String paramValue = httpRequest.getParameter(paramName);
                if (params.length() > 0) {
                    params.append("&");
                }
                params.append(paramName).append("=").append(paramValue);
            }
            log.debug("Found request: method={}, uri={}, params={}", httpRequest.getMethod(),
                    httpRequest.getRequestURI(), params);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            int status = ((HttpServletResponse) response).getStatus();
            log.info("Finished request: method={}, uri={}, status={}, duration={}ms",
                    httpRequest.getMethod(),
                    httpRequest.getRequestURI(),
                    status,
                    duration);
        }
    }
}
