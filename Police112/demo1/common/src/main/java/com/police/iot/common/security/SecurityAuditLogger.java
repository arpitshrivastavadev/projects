package com.police.iot.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Centralised security audit log.
 *
 * All entries are written to the "SECURITY" logger which in production
 * gets its own Logstash index ("police-ecp-security-*") for compliance.
 *
 * Events logged:
 *  - AUTH_FAILURE      — bad/missing JWT
 *  - TENANT_MISMATCH   — header tenantId != JWT claim tenantId
 *  - ACCESS_DENIED     — 403 from Spring Security
 *  - IDEMPOTENCY_CONFLICT — duplicate key with different payload (409)
 *
 * Interview talking point:
 *   "For a government public-safety platform, security audit logs are a
 *    compliance requirement. I separate them into their own index so
 *    security team can query auth failures independently of application logs,
 *    and we alert on any burst of auth failures as a brute-force signal."
 */
@Slf4j(topic = "SECURITY_AUDIT")
@Component
public class SecurityAuditLogger {

    public void authFailure(String reason, String remoteIp, String uri) {
        log.warn("event=AUTH_FAILURE reason=\"{}\" client_ip={} uri={}", reason, remoteIp, uri);
    }

    public void tenantMismatch(String headerTenantId, String jwtTenantId, String uri) {
        log.warn("event=TENANT_MISMATCH header_tenant={} jwt_tenant={} uri={}",
                headerTenantId, jwtTenantId, uri);
    }

    public void accessDenied(String tenantId, String uri, String method) {
        log.warn("event=ACCESS_DENIED tenantId={} method={} uri={}", tenantId, method, uri);
    }

    public void idempotencyConflict(String idempotencyKey, String tenantId, String uri) {
        log.warn("event=IDEMPOTENCY_CONFLICT key={} tenantId={} uri={}",
                idempotencyKey, tenantId, uri);
    }

    public void suspiciousActivity(String description, String tenantId, String remoteIp) {
        log.error("event=SUSPICIOUS_ACTIVITY description=\"{}\" tenantId={} client_ip={}",
                description, tenantId, remoteIp);
    }
}
