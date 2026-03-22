# Issue Report: UI "Failed to create officer" / "Failed to fetch"

## Problem Summary

During UI validation, officer creation from `dashboard.html` failed with:
- `Failed to create officer: Failed to fetch`

In parallel, API checks showed mixed behavior:
- Direct `curl` calls sometimes succeeded.
- Browser-based requests failed intermittently.

## Actual Root Causes

1. Port `8081` conflict with host process  
   A separate host Java process was listening on `localhost:8081`, so requests were not always reaching the Docker `device-service` container.

2. Tenant filter enforced too broadly  
   `TenantFilter` required `X-Tenant-Id` for non-API paths as well, which broke static UI access in some runs.

3. CORS/preflight path mismatch during UI testing  
   UI served from a different origin (`localhost:5500`) triggered browser preflight and header validation path issues.

4. Security matcher misconfiguration introduced during fixes  
   Invalid request matcher patterns (`/**/..`) caused request parsing/runtime issues.

## Resolution Applied

1. Removed port ambiguity
- Stopped conflicting host process on `8081`.
- Recreated Docker `device-service` container and revalidated binding.

2. Scoped tenant enforcement
- Updated `TenantFilter` to enforce tenant header only for `/api/v1/police/**`.
- Allowed browser document/static access path behavior.

3. Stabilized UI access path
- Copied UI to `device-service` static resources.
- Access UI from same origin:
  - `http://localhost:8081/dashboard.html`

4. Corrected security config
- Removed invalid request matcher patterns from `SecurityConfig`.

5. Validation results
- `GET http://localhost:8081/dashboard.html` -> `200`
- `POST http://localhost:8081/api/v1/police/officers` with `X-Tenant-Id` -> `200`

## How To Avoid This In Future

1. Always verify port ownership before testing
```bash
lsof -nP -iTCP:8081 -sTCP:LISTEN
```
Ensure only intended service owns `8081`.

2. Prefer same-origin UI during backend validation
- Use `http://localhost:8081/dashboard.html` for this project.
- Avoid cross-origin static hosting unless CORS is explicitly tested.

3. Keep tenant checks API-scoped
- Apply tenant header enforcement only to protected API routes, not static/document endpoints.

4. Validate security matchers after edits
- Avoid ambiguous/invalid glob-style matcher patterns.
- Run quick smoke checks after config changes:
  - `/dashboard.html`
  - `/actuator/health`
  - `/api/v1/police/officers` with tenant header

5. Use clean rebuild when behavior is inconsistent
```bash
docker compose build --no-cache device-service
docker compose up -d --force-recreate device-service
```

## Recommended Quick Verification Checklist

1. `docker compose ps`
2. `curl -i http://localhost:8081/dashboard.html`
3. `curl -i http://localhost:8081/api/v1/police/officers -H "X-Tenant-Id: NYPD"`
4. Create a new officer with a unique `badgeNumber`
