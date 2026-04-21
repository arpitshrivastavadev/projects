package com.police.iot.common.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class TenantFilter implements Filter {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    private final AuthenticatedTenantResolver authenticatedTenantResolver;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = httpRequest.getRequestURI();

        if (!path.startsWith("/api/v1/police")) {
            chain.doFilter(request, response);
            return;
        }

        if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Optional<String> authenticatedTenantId = authenticatedTenantResolver.resolveTenantId(authentication);

        if (authenticatedTenantId.isEmpty()) {
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
            httpResponse.getWriter().write("Authenticated tenant claim is required");
            return;
        }

        String requestedTenantId = httpRequest.getHeader(TENANT_HEADER);
        if (requestedTenantId != null && !requestedTenantId.isBlank() && !requestedTenantId.equals(authenticatedTenantId.get())) {
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
            httpResponse.getWriter().write("Tenant header does not match authenticated tenant");
            return;
        }

        try {
            TenantContext.setTenantId(authenticatedTenantId.get());
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
