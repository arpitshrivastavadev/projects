# End-to-End Testing Guide

This guide explains how to test the Police IoT microservices locally, what to send from Postman, what to verify outside Postman, and what is happening internally during each flow.

It is written to be practical first, interview-friendly second.

## 1. Overview

### What this application does

This project models a police IoT platform with two main runtime flows:

- **Data plane**: telemetry comes from the API layer, goes through Kafka, gets processed by `event-service`, and updates a Redis digital twin.
- **Control plane**: commands are created and tracked in `command-service`, stored in PostgreSQL, and moved through lifecycle statuses.
- **Cross-cutting concerns**: JWT tenant security, API idempotency, event idempotency, stale-event protection, metrics, logs, retry/DLQ, and distributed tracing.

### Main services and their roles

| Service | Main job | Typical port |
|---|---|---:|
| `device-service` | API layer for police domain APIs and telemetry publish | `8081` or `18081` |
| `event-service` | Kafka consumer, Redis digital twin updater, retry/DLQ handling | `8083` or `18083` |
| `command-service` | Command create/fetch/ack APIs with status history | `8082` or `18082` |
| `sim-service` | Telemetry simulator for bulk/demo load | varies, usually not needed for controlled tests |
| `common` | Shared DTOs, tenant/security helpers, logging, tracing utilities | n/a |

### Data plane vs control plane vs cross-cutting concerns

| Area | Meaning in this project |
|---|---|
| Data plane | Real-time telemetry flow: HTTP -> Kafka -> consumer -> Redis |
| Control plane | Command creation and lifecycle tracking in Postgres |
| Cross-cutting concerns | JWT auth, tenant resolution, idempotency, metrics, logs, tracing |

## 2. Prerequisites

### What must be running

For a full local end-to-end run, you typically need:

- Kafka
- Redis
- PostgreSQL
- Jaeger
- `device-service`
- `event-service`
- `command-service`

You do **not** need `sim-service` for controlled Postman testing.

### Ports used

| Component | Port |
|---|---:|
| `device-service` | `8081` or `18081` |
| `command-service` | `8082` or `18082` |
| `event-service` | `8083` or `18083` |
| Kafka external bootstrap | `29092` |
| Redis | `6379` |
| PostgreSQL | `5432` |
| Jaeger UI | `16686` |
| OTLP HTTP | `4318` |

### Docker dependencies

The repo uses Docker Compose for Kafka, Redis, and PostgreSQL. Jaeger can be started separately with:

```bash
docker compose -f docs/phase-7-distributed-tracing/jaeger-docker-compose.yaml up -d
```

### Java and Gradle

You need:

- Java version compatible with the project Gradle build
- Gradle wrapper via `./gradlew`

Typical local startup commands:

```bash
docker compose up -d postgres redis zookeeper kafka kafka-init
docker compose -f docs/phase-7-distributed-tracing/jaeger-docker-compose.yaml up -d

env GRADLE_USER_HOME=$PWD/.gradle-local ./gradlew :device-service:bootRun --args='--spring.profiles.active=dev --server.port=18081'
env GRADLE_USER_HOME=$PWD/.gradle-local ./gradlew :event-service:bootRun --args='--spring.profiles.active=dev --server.port=18083'
env DB_NAME=police_command_db GRADLE_USER_HOME=$PWD/.gradle-local ./gradlew :command-service:bootRun --args='--spring.profiles.active=dev --server.port=18082'
```

### How to confirm services are up

Run:

```bash
curl -sS -i http://127.0.0.1:18081/actuator/health
curl -sS -i http://127.0.0.1:18082/actuator/health
curl -sS -i http://127.0.0.1:18083/actuator/health
```

Expected: `200 OK` with `{"status":"UP"}` or equivalent health JSON.

## 3. Architecture Flow Before Testing

### Telemetry flow

Simple view:

```text
device-service -> Kafka -> event-service -> Redis
```

What happens internally:

1. You call `POST /api/v1/telemetry` on `device-service`.
2. `device-service` fills missing `eventId` or `timestamp` if needed.
3. It publishes JSON telemetry to Kafka topic `police-telemetry`.
4. Kafka carries headers such as `X-Correlation-ID` and `traceparent`.
5. `event-service` consumes the record.
6. It checks event idempotency in Redis using `SETNX + TTL`.
7. It checks stale ordering against the current Redis snapshot.
8. If the event is new and newer, it updates the Redis digital twin.
9. If processing fails, retry/DLQ rules apply.

### Command flow

Simple view:

```text
command-service -> PostgreSQL -> status history
```

What happens internally:

1. You call `POST /api/v1/commands`.
2. `command-service` authenticates and resolves tenant from JWT.
3. It stores a command row in `device_commands` with status `CREATED`.
4. It stores a matching history row in `command_status_history`.
5. Placeholder dispatch logic immediately moves it to `SENT`.
6. Later, `POST /api/v1/commands/{id}/acks` moves it to `ACKED`, `FAILED`, or `TIMED_OUT`.

### Security flow

Simple view:

```text
JWT -> tenant claim resolution -> tenant-aware authorization
```

What happens internally:

1. The service reads the Bearer token.
2. In `dev`, a local HS256 JWT filter validates issuer, signature, and expiry.
3. The tenant is resolved from claims such as `tenant_id`, `tenantId`, `tenant`, or `tid`.
4. The request is allowed only if the tenant claim exists.
5. If `X-Tenant-Id` is present and does not match the JWT tenant, the request is rejected.

### Idempotency flow

There are two different idempotency mechanisms.

#### API idempotency

Used in `device-service` for covered write APIs:

- `POST /api/v1/police/officers`
- `POST /api/v1/police/vehicles`

What happens internally:

1. The request must include `Idempotency-Key`.
2. The service stores tenant + route + HTTP method + key + request hash.
3. If the same key and same payload arrives again, it replays the original response.
4. If the same key but different payload arrives, it returns `409 Conflict`.
5. The scope is tenant-aware, so the same key under different tenants does not collide.

#### Event idempotency

Used in `event-service` for Kafka telemetry processing.

What happens internally:

1. The consumer claims an event marker in Redis.
2. Preferred key: `tenantId + deviceId + eventId`
3. Fallback key: `tenantId + deviceId + timestamp`
4. Duplicate events are skipped before snapshot update.

### Tracing flow

Simple view:

```text
HTTP -> Kafka producer -> Kafka consumer -> Redis
```

What happens internally:

1. `device-service` starts an HTTP trace span for `POST /api/v1/telemetry`.
2. Kafka producer observation creates a producer span.
3. The `traceparent` header is injected into the Kafka record.
4. `event-service` extracts the trace context from Kafka headers.
5. It continues the same trace in the consumer span.
6. The Redis store span becomes a child of the consumer span.
7. Jaeger should show one trace across all these hops.

## 4. JWT / Auth Setup for Postman

### Dev JWT mode

`device-service` and `command-service` support local/dev JWT auth.

#### Device-service dev JWT config

- issuer: `police-iot-device-local`
- secret: `local-device-service-jwt-signing-secret-32b`

#### Command-service dev JWT config

- issuer: `police-iot-command-local`
- secret: `local-command-service-jwt-signing-secret-32`

### Tenant claim names accepted

Any one of these works:

- `tenant_id`
- `tenantId`
- `tenant`
- `tid`

### Token generation commands

#### Token A: valid NYPD token for device-service

```bash
python3 - <<'PY'
import json, time, base64, hmac, hashlib
secret = b'local-device-service-jwt-signing-secret-32b'
issuer = 'police-iot-device-local'
def b64url(data): return base64.urlsafe_b64encode(data).rstrip(b'=').decode()
header = {'alg':'HS256','typ':'JWT'}
now = int(time.time())
payload = {
  'iss': issuer,
  'scope': 'police.read police.write',
  'iat': now,
  'exp': now + 3600,
  'sub': 'device-nypd-1',
  'tenant_id': 'NYPD'
}
h = b64url(json.dumps(header, separators=(',',':')).encode())
p = b64url(json.dumps(payload, separators=(',',':')).encode())
s = b64url(hmac.new(secret, f'{h}.{p}'.encode(), hashlib.sha256).digest())
print(f'{h}.{p}.{s}')
PY
```

#### Token B: valid LAPD token for device-service

Use the same script and change:

```python
'tenant_id': 'LAPD'
```

#### Token C: missing tenant token

Use the same script and remove:

```python
'tenant_id': 'NYPD'
```

#### Token D: invalid issuer token

Use the same script and change:

```python
'iss': 'bad-issuer'
```

### Command-service tokens

For `command-service`, use the same approach with:

- secret: `local-command-service-jwt-signing-secret-32`
- issuer: `police-iot-command-local`
- scope: `commands.read commands.write`

### How to use in Postman

Set the header:

```text
Authorization: Bearer <your-token>
```

Postman Authorization tab:

- Type: `Bearer Token`
- Token: `{{bearer_token_nypd}}` or `{{bearer_token_lapd}}`

## 5. Postman Setup

### Suggested Postman environment variables

| Variable | Example value |
|---|---|
| `device_service_base_url` | `http://127.0.0.1:18081` |
| `command_service_base_url` | `http://127.0.0.1:18082` |
| `event_service_base_url` | `http://127.0.0.1:18083` |
| `bearer_token_nypd` | generated device or command token |
| `bearer_token_lapd` | generated device or command token |
| `correlation_id` | `corr-{{$timestamp}}` |
| `telemetry_device_id` | `trace-final-device-1` |
| `command_id` | empty initially, store after command create |

### Suggested collection structure

```text
Police IoT E2E
  1. Health
  2. Security
  3. Device Service
  4. Telemetry Flow
  5. Event Processing
  6. API Idempotency
  7. Command Service
  8. Observability
```

### Suggested request naming

- `Health - device-service`
- `Security - unauthenticated officers`
- `Telemetry - publish valid event`
- `Telemetry - duplicate event`
- `Telemetry - stale event`
- `Officers - idempotent create`
- `Commands - create`
- `Commands - ack`
- `Observability - event metrics`

## 6. End-to-End Test Scenarios

For each scenario below:

- use the exact HTTP request in Postman
- then verify external state in Redis, Postgres, metrics, logs, or Jaeger
- understand what internal application path that request is exercising

### A. Health Check

#### What to call

| Service | Method | Endpoint |
|---|---|---|
| device-service | `GET` | `{{device_service_base_url}}/actuator/health` |
| command-service | `GET` | `{{command_service_base_url}}/actuator/health` |
| event-service | `GET` | `{{event_service_base_url}}/actuator/health` |

#### Headers

No auth required.

#### Expected response

- `200 OK`
- JSON health body

#### What this proves

- the service is reachable
- the Spring app started
- open endpoints are available without auth

#### What is happening internally

These are framework health endpoints. They do not exercise business logic, but they confirm the app booted successfully and actuator security rules are correct.

### B. Security Validation

#### Scenario B1: unauthenticated request rejected

Call:

```text
GET {{device_service_base_url}}/api/v1/police/officers
```

No auth header.

Expected:

- rejection
- often `403` in this local setup because tenant/security filters run before a normal business response

What to verify:

- request does not reach business data

What this proves:

- protected APIs are not open anonymously

#### Scenario B2: missing tenant claim rejected

Call the same endpoint with Token C.

Expected:

- `403`
- message similar to `Authenticated tenant claim is required`

What this proves:

- tenant must come from authenticated claims

#### Scenario B3: invalid issuer rejected

Call the same endpoint with Token D.

Expected:

- `401`
- invalid issuer style error

What this proves:

- JWT issuer validation is active

#### Scenario B4: tenant header mismatch rejected

Call with valid NYPD token and mismatched header:

```text
Authorization: Bearer {{bearer_token_nypd}}
X-Tenant-Id: LAPD
```

Expected:

- `403`
- message like `Tenant header does not match authenticated tenant`

What this proves:

- header cannot override JWT-derived tenant

### C. Device-Service CRUD / Protected API Check

Representative request:

```text
GET {{device_service_base_url}}/api/v1/police/officers
Authorization: Bearer {{bearer_token_nypd}}
```

Optional matching header:

```text
X-Tenant-Id: NYPD
```

Expected:

- `200 OK`
- only NYPD-visible officer records

What to verify:

- with LAPD token you should not see NYPD data

What this proves:

- request passed auth
- tenant was resolved from JWT
- data access is tenant-aware

What is happening internally:

1. JWT is validated.
2. Tenant is resolved from claims.
3. The tenant filter sets `TenantContext`.
4. Repository/service logic uses tenant-aware reads.

### D. Telemetry Publish Flow

#### Endpoint

```text
POST {{device_service_base_url}}/api/v1/telemetry
```

#### Headers

```text
Authorization: Bearer {{bearer_token_nypd}}
X-Correlation-ID: {{correlation_id}}
Content-Type: application/json
```

#### Body

```json
{
  "eventId": "evt-telemetry-1",
  "deviceId": "test-device-1",
  "tenantId": "NYPD",
  "timestamp": "2026-04-22T05:00:00Z",
  "latitude": 40.7128,
  "longitude": -74.0060,
  "speed": 120.0,
  "batteryLevel": 81,
  "officerId": "OFF-TRACE-1",
  "vehicleId": "VEH-TRACE-1",
  "status": "ACTIVE"
}
```

#### Expected response

- `202 Accepted`

#### What to verify

In Redis:

```bash
redis-cli -h 127.0.0.1 -p 6379 GET twin:snapshot:test-device-1
```

In event-service readback:

```text
GET {{event_service_base_url}}/api/v1/telemetry/device/test-device-1
```

Expected:

- Redis snapshot exists
- event-service readback returns the same snapshot

#### What this proves

- HTTP request accepted in `device-service`
- Kafka publish succeeded
- `event-service` consumed the message
- Redis digital twin update worked

#### What is happening internally

1. `device-service` validates auth and builds telemetry event.
2. It sends the event to Kafka topic `police-telemetry`.
3. Kafka carries headers including correlation and trace context.
4. `event-service` consumes the record.
5. It checks event idempotency.
6. It checks stale ordering.
7. It writes the latest snapshot to Redis.

### E. Event Idempotency

#### Test

Send the exact same telemetry event twice:

- same `tenantId`
- same `deviceId`
- same `eventId`
- same `timestamp`

#### Expected response

The HTTP publisher may still return `202` both times because publishing itself succeeded.

#### What to verify

In event-service logs:

- duplicate skip log

In metrics:

```text
GET {{event_service_base_url}}/actuator/prometheus
```

Look for:

- `event_kafka_consume_duplicates_skipped_total`
- `event_idempotency_claim_total{result="duplicate"}`

In Redis:

- snapshot should not be incorrectly reprocessed or changed

#### What this proves

- duplicate events are suppressed before business processing

#### What is happening internally

`event-service` uses a Redis marker to claim event processing. If the same logical event appears again, the claim fails, so the event is skipped.

### F. Stale Event Protection

#### Test

1. Send a newer event first with timestamp `T2`
2. Send an older event second with timestamp `T1 < T2`

Use the same device and tenant.

#### Expected result

- newer event updates Redis
- older event is ignored

#### What to verify

In Redis:

- final stored timestamp remains `T2`

In logs:

- stale skip log from `event-service`

In metrics:

- `event_snapshot_stale_skipped_total`

#### What this proves

- the digital twin is protected against out-of-order older data

#### What is happening internally

`TelemetrySnapshotService.storeTelemetryIfNewer(...)` compares incoming telemetry timestamp with the current snapshot timestamp. If the incoming event is older or equal, it does not overwrite the snapshot.

### G. API Idempotency in device-service

Covered endpoints:

- `POST /api/v1/police/officers`
- `POST /api/v1/police/vehicles`

#### Scenario G1: missing `Idempotency-Key`

Request:

```text
POST {{device_service_base_url}}/api/v1/police/officers
Authorization: Bearer {{bearer_token_nypd}}
Content-Type: application/json
```

Body:

```json
{
  "userId": "postman-user-1",
  "name": "Officer Postman",
  "badgeNumber": "B-POSTMAN-001",
  "status": "ACTIVE"
}
```

Expected:

- `400`
- error similar to `Idempotency-Key header is required for this endpoint`

#### Scenario G2: same key + same payload

Headers:

```text
Authorization: Bearer {{bearer_token_nypd}}
Idempotency-Key: idem-officer-1
Content-Type: application/json
```

Send the same request twice.

Expected:

- first request creates the entity
- second request replays the same response
- same `id` in both responses
- no duplicate row in DB

#### Scenario G3: same key + different payload

Reuse:

```text
Idempotency-Key: idem-officer-1
```

But change the officer payload.

Expected:

- `409 Conflict`
- no duplicate row

#### Scenario G4: same key across tenants

Use:

- Token A for NYPD
- Token B for LAPD
- same `Idempotency-Key`

Expected:

- both succeed independently
- no cross-tenant collision

#### What to verify outside Postman

Check `t_idempotency_record` and business tables in Postgres.

#### What this proves

- write APIs are safe against client retries
- replay works correctly
- idempotency scope is tenant-aware

#### What is happening internally

The idempotency aspect stores the first successful response against tenant + route + method + key. Repeated same-payload requests return the stored response instead of creating another entity.

### H. Command-Service Create Flow

#### Endpoint

```text
POST {{command_service_base_url}}/api/v1/commands
```

#### Headers

```text
Authorization: Bearer <command-service NYPD token>
Content-Type: application/json
```

#### Body

```json
{
  "targetDeviceId": "cmd-device-1",
  "commandType": "LOCK_DOORS",
  "payload": "LOCK"
}
```

#### Expected response

- `201 Created`
- response contains command ID
- status ends at `SENT`
- history includes:
  - `CREATED`
  - `SENT`

#### What to verify in DB

- row in `device_commands`
- rows in `command_status_history`

#### What this proves

- command persistence works
- placeholder dispatch lifecycle works

#### What is happening internally

The service first stores the command as `CREATED`, then immediately records a transition to `SENT` to simulate dispatch progression.

### I. Command Fetch / Tenant Isolation

#### Scenario I1: fetch with correct tenant

```text
GET {{command_service_base_url}}/api/v1/commands/{{command_id}}
Authorization: Bearer <command-service NYPD token>
```

Expected:

- `200`
- correct command
- same tenant

#### Scenario I2: fetch with wrong tenant

Use LAPD token against the NYPD command.

Expected:

- typically `404`

#### What this proves

- commands are tenant-scoped
- no cross-tenant read leakage

### J. Command ACK / FAILED / TIMED_OUT

#### ACK transition

```text
POST {{command_service_base_url}}/api/v1/commands/{{command_id}}/acks
Authorization: Bearer <command-service NYPD token>
Content-Type: application/json
```

Body:

```json
{
  "status": "ACKED",
  "reason": "Device acknowledged receipt"
}
```

Expected:

- `200`
- command status becomes `ACKED`
- history row `SENT -> ACKED`

#### FAILED transition

Create another command and send:

```json
{
  "status": "FAILED",
  "reason": "Device rejected command"
}
```

Expected:

- status becomes `FAILED`
- history row recorded

#### TIMED_OUT transition

Create another command and send:

```json
{
  "status": "TIMED_OUT",
  "reason": "Ack timeout expired"
}
```

Expected:

- status becomes `TIMED_OUT`
- history row recorded

#### Invalid transition

Try ACK on a command already in `ACKED`.

Expected:

- `400`
- no duplicate history row

#### What this proves

- lifecycle transitions are enforced correctly
- invalid state transitions are blocked

### K. Observability Validation

#### Metrics endpoints

| Service | Endpoint |
|---|---|
| device-service | `{{device_service_base_url}}/actuator/prometheus` |
| command-service | `{{command_service_base_url}}/actuator/prometheus` |
| event-service | `{{event_service_base_url}}/actuator/prometheus` |

#### Important counters to look for

`command-service`:

- `commands_created_total`
- `commands_acked_total`
- `commands_failed_total`
- `commands_timed_out_total`

`event-service`:

- `event_kafka_consume_success_total`
- `event_kafka_consume_failures_total`
- `event_kafka_consume_retry_attempts_total`
- `event_kafka_consume_failures_routed_total`
- `event_kafka_consume_duplicates_skipped_total`
- `event_snapshot_stale_skipped_total`

#### Logs to look for

- `traceId`
- `spanId`
- `correlationId`
- `tenantId` where relevant
- command lifecycle logs
- Kafka consume logs
- stale or duplicate skip logs

#### Jaeger checks

Telemetry flow should show one trace with:

```text
http post /api/v1/telemetry
  -> police-telemetry send
     -> police-telemetry receive
        -> event.redis.store_if_newer
```

Command flow should show HTTP traces for:

- create command
- ack command

#### What this proves

- the system is observable
- troubleshooting production incidents is realistic

## 7. What to Check Outside Postman

### Redis verification

Check a digital twin directly:

```bash
redis-cli -h 127.0.0.1 -p 6379 GET twin:snapshot:test-device-1
```

Useful Redis key patterns:

- `twin:snapshot:<deviceId>`
- `event:idempotency:<tenant>:<device>:event:<eventId>`
- `event:idempotency:<tenant>:<device>:ts:<timestamp>`

### PostgreSQL verification

#### Commands and history

```sql
select command_id, tenant_id, target_device_id, command_type, status, created_at, updated_at
from device_commands
order by created_at desc;

select command_id, from_status, to_status, reason, changed_at
from command_status_history
order by changed_at desc;
```

#### API idempotency

```sql
select tenant_id, route, http_method, idempotency_key, request_hash, response_status, response_body
from t_idempotency_record
order by updated_at desc;
```

### Kafka header verification

If you need to prove tracing or correlation header propagation, consume a specific record and print headers:

```bash
docker compose exec -T kafka kafka-console-consumer \
  --bootstrap-server kafka:9092 \
  --topic police-telemetry \
  --from-beginning \
  --max-messages 1 \
  --property print.headers=true \
  --property print.key=true \
  --timeout-ms 5000
```

Look for:

- `traceparent`
- `X-Correlation-ID`

### Jaeger UI checks

Open:

```text
http://localhost:16686
```

Suggested services to inspect:

- `device-service`
- `event-service`
- `command-service`

## 8. Postman Collection Design Suggestion

Suggested folders:

| Folder | Purpose |
|---|---|
| `01 Health` | actuator checks |
| `02 Security` | negative auth and tenant cases |
| `03 Device APIs` | officers, vehicles, incidents |
| `04 Telemetry Flow` | publish, duplicate, stale |
| `05 Event Verification` | readback and metrics |
| `06 API Idempotency` | officers and vehicles write replay/conflict |
| `07 Commands` | create, fetch, ack, failed, timeout |
| `08 Observability` | metrics and trace validation references |

Suggested order:

1. health
2. security
3. representative protected device API
4. telemetry publish
5. event idempotency
6. stale protection
7. API idempotency
8. command create/fetch/ack
9. metrics and tracing

## 9. Troubleshooting Guide

### Service not starting

Check:

- port already in use
- wrong Java version
- missing Kafka, Redis, or Postgres
- invalid DB name for `command-service`

Useful commands:

```bash
lsof -nP -i :8081
lsof -nP -i :8082
lsof -nP -i :8083
```

### Invalid token

Symptoms:

- `401`
- invalid issuer
- signature/expiry failures

Check:

- issuer matches service-specific dev config
- correct secret
- token not expired
- using device token for device APIs and command token for command APIs

### Redis not updating

Check:

- event-service is running
- Kafka record actually published
- duplicate or stale event was intentionally skipped
- Redis is reachable on `6379`

### Kafka not consuming

Check:

- topic exists
- event-service logs
- bootstrap server is correct
- consumer is subscribed to `police-telemetry`

### Jaeger not showing trace

Check:

- Jaeger is running
- OTLP endpoint is reachable
- use a fresh request
- inspect service list in Jaeger UI

### Wrong tenant access

Check:

- token actually contains `tenant_id`
- `X-Tenant-Id` is either absent or matches
- you did not reuse the wrong service token

### Idempotency replay not working

Check:

- endpoint is actually covered
- `Idempotency-Key` is present
- same key used with exactly same payload for replay test
- same key with changed payload should return `409`

### Flyway DB issues

This is especially important for `command-service`.

If it shares a DB whose `flyway_schema_history` already contains a different service’s version `1.0.0`, startup may fail with a migration conflict. A simple local workaround is using a dedicated DB such as `police_command_db`.

## 10. Interview Revision Notes

### What each major test proves

| Test | What it proves |
|---|---|
| Health | services are up and actuator security is correct |
| Security | JWT validation and tenant isolation are enforced |
| Telemetry publish | data enters the system correctly |
| Event idempotency | duplicate Kafka events do not reprocess |
| Stale protection | older data does not overwrite fresh state |
| API idempotency | write APIs are safe for retries |
| Command lifecycle | command state model works correctly |
| Observability | metrics, logs, and tracing make flows debuggable |

### How to explain telemetry flow

“A client sends telemetry to `device-service`. It publishes the message to Kafka with correlation and tracing headers. `event-service` consumes it, applies event idempotency and stale-order checks, and stores the latest device snapshot in Redis as the digital twin.”

### How to explain command flow

“A client creates a command through `command-service`. The command is stored in PostgreSQL, a history row is recorded, and the status moves from `CREATED` to `SENT`. Later, an ack endpoint moves it to `ACKED`, `FAILED`, or `TIMED_OUT`, while preserving lifecycle history.”

### How to explain security

“JWT is validated in each service. The tenant is not trusted from a raw header anymore. It is resolved from JWT claims, and any mismatch between `X-Tenant-Id` and the JWT tenant is rejected.”

### How to explain tracing

“A single distributed trace starts on the HTTP request in `device-service`, continues through the Kafka producer span, crosses the Kafka boundary into `event-service`, and includes the Redis update span. This gives true end-to-end visibility across asynchronous processing.”

### 2-minute summary of the application

“This system has a data plane and a control plane. The data plane handles telemetry: `device-service` receives telemetry, Kafka transports it, `event-service` processes it, and Redis stores the latest digital twin. The control plane handles commands: `command-service` stores commands and lifecycle transitions in PostgreSQL. Security is tenant-aware using JWT claims, retries and DLQ protect Kafka processing, idempotency prevents duplicate writes and duplicate event processing, stale-event protection keeps Redis accurate, and distributed tracing plus metrics make the whole path observable end to end.”
