package com.police.iot.common.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
public class TenantFilter implements Filter {

    public static final String TENANT_HEADER = "X-Tenant-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = httpRequest.getRequestURI();
        String accept = httpRequest.getHeader("Accept");

        // Allow browser document requests without tenant header.
        if ("GET".equalsIgnoreCase(httpRequest.getMethod())
                && accept != null
                && accept.contains("text/html")) {
            chain.doFilter(request, response);
            return;
        }

        // Tenant enforcement is only required for device-service police APIs.
        if (!path.startsWith("/api/v1/police")) {
            chain.doFilter(request, response);
            return;
        }

        if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String tenantId = httpRequest.getHeader(TENANT_HEADER);

        if (tenantId == null || tenantId.isEmpty()) {
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            httpResponse.getWriter().write("X-Tenant-Id header is missing");
            return;
        }

        try {
            TenantContext.setTenantId(tenantId);
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
