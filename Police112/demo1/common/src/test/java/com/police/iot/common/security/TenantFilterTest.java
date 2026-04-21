package com.police.iot.common.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantFilterTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void filterSetsAndClearsTenantContext() throws Exception {
        AuthenticatedTenantResolver resolver = mock(AuthenticatedTenantResolver.class);
        TenantFilter tenantFilter = new TenantFilter(resolver);

        TestingAuthenticationToken authentication = new TestingAuthenticationToken("user", "pw");
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(resolver.resolveTenantId(any())).thenReturn(Optional.of("tenant-a"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/police/officers");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        doAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.getTenantId());
            return null;
        }).when(filterChain).doFilter(any(), any());

        tenantFilter.doFilter(request, response, filterChain);

        assertNull(TenantContext.getTenantId());
    }

    @Test
    void filterRejectsMismatchedTenantHeader() throws Exception {
        AuthenticatedTenantResolver resolver = mock(AuthenticatedTenantResolver.class);
        TenantFilter tenantFilter = new TenantFilter(resolver);

        TestingAuthenticationToken authentication = new TestingAuthenticationToken("user", "pw");
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(resolver.resolveTenantId(any())).thenReturn(Optional.of("tenant-a"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/police/officers");
        request.addHeader(TenantFilter.TENANT_HEADER, "tenant-b");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        tenantFilter.doFilter(request, response, filterChain);

        assertEquals(403, response.getStatus());
        verify(filterChain, never()).doFilter(any(), any());
        assertNull(TenantContext.getTenantId());
    }
}
