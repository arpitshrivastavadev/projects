package com.police.iot.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class AuthenticatedTenantResolver {

    private static final List<String> TENANT_CLAIM_KEYS = List.of("tenant_id", "tenantId", "tenant", "tid");

    public Optional<String> resolveTenantId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || isAnonymous(authentication.getAuthorities())) {
            return Optional.empty();
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof Map<?, ?> principalMap) {
            return resolveFromAttributes(principalMap);
        }

        Optional<String> claimResolved = resolveFromMethod(principal, "getClaims");
        if (claimResolved.isPresent()) {
            return claimResolved;
        }

        return resolveFromMethod(principal, "getAttributes");
    }

    private boolean isAnonymous(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream().anyMatch(authority -> "ROLE_ANONYMOUS".equals(authority.getAuthority()));
    }

    private Optional<String> resolveFromMethod(Object principal, String methodName) {
        if (principal == null) {
            return Optional.empty();
        }

        try {
            Method method = principal.getClass().getMethod(methodName);
            Object value = method.invoke(principal);
            if (value instanceof Map<?, ?> map) {
                return resolveFromAttributes(map);
            }
        } catch (ReflectiveOperationException ignored) {
            return Optional.empty();
        }

        return Optional.empty();
    }

    private Optional<String> resolveFromAttributes(Map<?, ?> attributes) {
        for (String claimKey : TENANT_CLAIM_KEYS) {
            Object value = attributes.get(claimKey);
            if (value instanceof String tenantId && !tenantId.isBlank()) {
                return Optional.of(tenantId);
            }
        }
        return Optional.empty();
    }
}
