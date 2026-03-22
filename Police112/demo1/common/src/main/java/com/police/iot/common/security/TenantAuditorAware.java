package com.police.iot.common.security;

import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
public class TenantAuditorAware implements AuditorAware<String> {

    @Override
    public Optional<String> getCurrentAuditor() {
        // In a real system, this would get the User Principle.
        // For this demo, we can use the Tenant ID or a system default.
        String tenant = TenantContext.getTenantId();
        return Optional.ofNullable(tenant).or(() -> Optional.of("system"));
    }
}
