# Phase 7 - JWT Runtime Auth for `device-service`

## Why Phase 4 and Phase 5 runtime verification was blocked

Phase 4 moved tenant resolution to trusted authenticated claims (`tenant_id`, `tenantId`, `tenant`, `tid`) via `AuthenticatedTenantResolver` and `TenantFilter`.
However, runtime auth was still HTTP Basic with a default `UserDetails` principal, which does not expose claims/attributes.
As a result, protected business endpoints (`/api/v1/police/**`) failed with:

`Authenticated tenant claim is required`

That prevented full end-to-end runtime validation of:
- trusted tenant resolution (Phase 4), and
- write idempotency execution under tenant context (Phase 5).

## What runtime auth path now exists

`device-service` now uses a bearer JWT authentication path aligned to Spring Security resource-server patterns for runtime requests:

- Requests use `Authorization: Bearer <jwt>`
- JWT is validated (issuer, expiration, HMAC-SHA256 signature) by `DevJwtAuthenticationFilter` in local/dev mode
- Spring Security context is populated with a claim-bearing authentication principal (`Map<String, Object>`)
- `TenantFilter` resolves tenant from trusted claims and sets `TenantContext`

### Local/dev JWT mode

For local/dev profile, the service enables an HS256 local bearer filter (`DevJwtAuthenticationFilter`) so testing can be done without standing up an external IdP.

Dev properties:

- `app.security.jwt.dev.enabled=true`
- `app.security.jwt.dev.issuer=police-iot-device-local`
- `app.security.jwt.dev.secret=local-device-service-jwt-signing-secret-32b`

In production, disable `app.security.jwt.dev.enabled` and replace this with standard `spring.security.oauth2.resourceserver.jwt.issuer-uri` or `jwk-set-uri` wiring.

## Supported tenant claim names

The existing resolver consumes any of:

- `tenant_id`
- `tenantId`
- `tenant`
- `tid`

## Example local token payload

```json
{
  "iss": "police-iot-device-local",
  "sub": "officer-local-1",
  "tenant_id": "tenant-a",
  "scope": "police.read police.write",
  "iat": 1713686400,
  "exp": 1893456000
}
```

## Generate a local dev token

Example with Python + `pyjwt`:

```bash
python3 - <<'PY'
import jwt, time
secret = "local-device-service-jwt-signing-secret-32b"
payload = {
  "iss": "police-iot-device-local",
  "sub": "officer-local-1",
  "tenant_id": "tenant-a",
  "scope": "police.read police.write",
  "iat": int(time.time()),
  "exp": int(time.time()) + 3600
}
print(jwt.encode(payload, secret, algorithm="HS256"))
PY
```

## Use the token

```bash
curl -i \
  -H "Authorization: Bearer <TOKEN>" \
  -H "X-Tenant-Id: tenant-a" \
  http://localhost:8081/api/v1/police/officers
```

If `X-Tenant-Id` is sent and does not match claim tenant, request is rejected (`403`).
