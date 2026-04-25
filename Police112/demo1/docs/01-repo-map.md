# Repo Map (Flow-Oriented)

## 1) Module-wise summary

| Module | Responsibility | Main runtime dependencies | Owns data in |
|---|---|---|---|
| `common` | Shared DTO/model/security/filter/kafka topic config used by all services | Spring Web/Security/JPA/Kafka | Shared code only (no standalone runtime DB) |
| `device-service` | Police domain write/query APIs (officers, vehicles, incidents) + telemetry publish API to Kafka | Spring Web, JPA, Security, Flyway, Kafka | PostgreSQL |
| `command-service` | Command lifecycle API (`create -> sent -> ack/failed/timed_out`) with tenant-scoped history | Spring Web, JPA, Security, Flyway | PostgreSQL |
| `event-service` | Kafka telemetry consumption, idempotency, retry/DLQ routing, Redis digital-twin snapshot read APIs | Spring Kafka, Redis, Resilience4j, Actuator | Redis |
| `sim-service` | Scheduled telemetry load generator publishing to Kafka | Spring Scheduling, Kafka, Resilience4j | No persistent store |

---

## 2) Important packages and files

### Root / build / infra
- Multi-module wiring: `settings.gradle`, root `build.gradle`.
- Local infra and service orchestration: `docker-compose.yml`.
- DB bootstrap helper: `infra/init-db.sh`.

### `common`
- Kafka topic beans: `common/src/main/java/com/police/iot/common/config/KafkaConfig.java`.
- Shared telemetry DTO: `common/src/main/java/com/police/iot/common/dto/PoliceTelemetry.java`.
- Shared tenant/correlation/log filters:
  - `common/src/main/java/com/police/iot/common/security/TenantFilter.java`
  - `common/src/main/java/com/police/iot/common/security/CorrelationIdFilter.java`
  - `common/src/main/java/com/police/iot/common/security/RequestLoggingFilter.java`
- Shared JPA base/domain models: `common/src/main/java/com/police/iot/common/model/*`.

### `device-service`
- Boot entry: `device-service/src/main/java/com/police/iot/device/DeviceServiceApplication.java`.
- Controllers:
  - `device-service/src/main/java/com/police/iot/device/controller/PoliceController.java`
  - `device-service/src/main/java/com/police/iot/device/controller/TelemetryPublishController.java`
- Kafka producer logic: `device-service/src/main/java/com/police/iot/device/service/TelemetryPublishService.java`.
- Security + filters registration: `device-service/src/main/java/com/police/iot/device/config/*`.
- Idempotent write AOP: `device-service/src/main/java/com/police/iot/device/idempotency/*`.
- DB migrations: `device-service/src/main/resources/db/migration/*`.
- Config: `device-service/src/main/resources/application*.yaml`.

### `command-service`
- Boot entry: `command-service/src/main/java/com/police/iot/command/CommandServiceApplication.java`.
- Controller: `command-service/src/main/java/com/police/iot/command/controller/CommandController.java`.
- Core business logic: `command-service/src/main/java/com/police/iot/command/service/CommandService.java`.
- Tenant/security filters: `command-service/src/main/java/com/police/iot/command/config/*`.
- Persistence: `command-service/src/main/java/com/police/iot/command/model/*`, `repository/*`.
- DB migrations + config: `src/main/resources/db/migration/*`, `application*.yaml`.

### `event-service`
- Boot entry: `event-service/src/main/java/com/police/iot/event/EventServiceApplication.java`.
- Kafka listeners: `event-service/src/main/java/com/police/iot/event/kafka/TelemetryConsumer.java`.
- Retry/DLQ config: `event-service/src/main/java/com/police/iot/event/config/KafkaConsumerErrorHandlingConfig.java`.
- Redis snapshot service: `event-service/src/main/java/com/police/iot/event/service/TelemetrySnapshotService.java`.
- Idempotency service: `event-service/src/main/java/com/police/iot/event/service/TelemetryEventIdempotencyService.java`.
- Read APIs: `event-service/src/main/java/com/police/iot/event/controller/TelemetryController.java`.
- Redis/security/config: `event-service/src/main/java/com/police/iot/event/config/*`, `application*.yaml`.

### `sim-service`
- Boot entry: `sim-service/src/main/java/com/police/iot/sim/SimServiceApplication.java`.
- Scheduler + publisher: `sim-service/src/main/java/com/police/iot/sim/simulator/DeviceLoadSimulator.java`.
- Executor tuning: `sim-service/src/main/java/com/police/iot/sim/config/SimulatorExecutorConfig.java`.
- Security/config: `sim-service/src/main/java/com/police/iot/sim/config/SecurityConfig.java`, `application*.yaml`.

---

## 3) Entry points (controllers, filters, Kafka listeners, schedulers)

### Controllers
- `device-service`
  - `PoliceController` (`/api/v1/police/...`)
  - `TelemetryPublishController` (`POST /api/v1/telemetry`)
- `command-service`
  - `CommandController` (`/api/v1/commands/...`)
- `event-service`
  - `TelemetryController` (`/api/v1/telemetry/...`)

### Filters
- Shared (`common`): `CorrelationIdFilter`, `RequestLoggingFilter`, `TenantFilter`.
- Service-specific:
  - `command-service`: `CommandTenantFilter`, optional `DevJwtAuthenticationFilter`.
  - `device-service`: optional `DevJwtAuthenticationFilter`, `DevCorsFilter` (dev helper).

### Kafka listeners
- `event-service`: `TelemetryConsumer.consumeMain(...)` on `police-telemetry`.
- `event-service`: `TelemetryConsumer.consumeRetry(...)` on `police-telemetry-retry`.

### Schedulers
- `sim-service`: `DeviceLoadSimulator.simulateTelemetry()` via `@Scheduled` fixed rate.

---

## 4) Shared/common classes to know first

- `PoliceTelemetry` (canonical event contract between producer/consumer services).
- `TenantContext` + tenant resolver/filter chain (`TenantFilter`, `CommandTenantFilter`, `AuthenticatedTenantResolver`).
- `CorrelationIdFilter` (request-level trace correlation propagated into Kafka headers).
- `BaseEntity` + domain entities (`Officer`, `PatrolVehicle`, `Incident`) used by JPA services.
- `KafkaConfig` (topic declarations).

---

## 5) Config map (application, Docker, Kafka, Redis, DB, security)

- **Application config per service**: `*/src/main/resources/application.yaml`, `application-dev.yaml`, `application-prod.yaml`.
- **Docker / local stack**: root `docker-compose.yml` + each service `Dockerfile`.
- **Kafka**:
  - Topic declarations: `common/.../KafkaConfig.java`.
  - Producer config: device/sim `application*.yaml`.
  - Consumer + retry/DLQ behavior: event `application.yaml` + `KafkaConsumerErrorHandlingConfig.java`.
- **Redis**:
  - Event-service connection and serializer: `event-service/application*.yaml`, `event-service/config/RedisConfig.java`.
- **Database (Postgres + Flyway)**:
  - Device/command datasource properties in `application-*.yaml`.
  - Migrations in each service `src/main/resources/db/migration`.
  - Local Postgres in `docker-compose.yml`.
- **Security**:
  - Per-service `SecurityConfig` classes.
  - Tenant enforcement filters (`TenantFilter` / `CommandTenantFilter`).
  - Dev JWT filters toggled by `app.security.jwt.dev.enabled`.

---

## 6) What to open first (fast interview prep order)

1. `settings.gradle` (module landscape)
2. `docker-compose.yml` (runtime architecture + infra)
3. `common/.../PoliceTelemetry.java` (event contract)
4. `sim-service/.../DeviceLoadSimulator.java` (event producer)
5. `event-service/.../TelemetryConsumer.java` (event consumer)
6. `event-service/.../TelemetrySnapshotService.java` (Redis projection model)
7. `device-service/.../PoliceController.java` + `TelemetryPublishService.java` (API + producer path)
8. `command-service/.../CommandController.java` + `CommandService.java` (command lifecycle/state transitions)
9. `common/.../TenantFilter.java` and service `SecurityConfig` classes (tenant/security flow)
10. `event-service/.../KafkaConsumerErrorHandlingConfig.java` (retry/DLQ robustness)

---

## 7) 10 interview questions from this repo

1. Explain end-to-end telemetry flow from generation to read API, and where ordering/idempotency are enforced.
2. Why does `event-service` use both idempotency keys and stale-timestamp checks? What failure does each prevent?
3. How does retry-topic + DLQ routing work in `KafkaConsumerErrorHandlingConfig`, and what metrics are emitted?
4. Compare tenant isolation in `device-service` vs `command-service` (`TenantFilter` vs `CommandTenantFilter`).
5. What trade-offs exist in `sim-service` scheduled batch + executor design (throughput vs backpressure vs drop behavior)?
6. Why is Redis used as a digital-twin snapshot store instead of querying Kafka or Postgres directly?
7. What are the implications of enabling `app.security.jwt.dev.enabled=true` in dev profile?
8. How would you evolve `command-service` from local state transitions to real async command dispatch/ack over Kafka?
9. Where are observability hooks (Micrometer/OTel) added, and how would you build SLO dashboards from current metrics?
10. If duplicate/out-of-order telemetry spikes in production, which classes/configs would you inspect first and why?
