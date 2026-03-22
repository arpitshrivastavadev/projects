# Microservices Purpose, APIs, and Code Flow

## Services Overview

1. `device-service` (`:8081`)
- Purpose: CRUD-style command/query APIs for police domain data (officers, vehicles, incidents).
- Storage: PostgreSQL.
- Multi-tenancy: `X-Tenant-Id` is read by `TenantFilter` and applied via `TenantContext`.

2. `event-service` (`:8083`)
- Purpose: Telemetry read APIs + Kafka consumer that builds Redis digital-twin snapshots.
- Storage: Redis (`twin:snapshot:*` keys).
- Input stream: Kafka topic `police-telemetry`.

3. `sim-service` (`:8084`)
- Purpose: Generates synthetic telemetry at interval and publishes to Kafka.
- Output stream: Kafka topic `police-telemetry`.

4. `command-service` (`:8082`)
- Purpose: Service shell for future command orchestration.
- Current state: No REST controllers or consumers implemented yet.

5. `common` (shared module)
- Purpose: Shared DTOs/entities/security filters/config used by multiple services.
- Key shared pieces: `PoliceTelemetry`, JPA models, tenant/correlation/request logging filters.

## API Endpoints

## `device-service` APIs

Base path: `/api/v1/police`

1. `GET /officers`
- Returns officers filtered by current tenant.
- Code path:
  - `PoliceController.getAllOfficers()`
  - `OfficerRepository.findByTenantId(TenantContext.getTenantId())`

2. `POST /officers`
- Creates officer for current tenant.
- Code path:
  - `PoliceController.createOfficer()`
  - sets `officer.tenantId` from `TenantContext`
  - `OfficerRepository.save(officer)`

3. `GET /vehicles`
- Returns vehicles for current tenant.
- Code path: `PoliceController.getAllVehicles()` -> `PatrolVehicleRepository.findByTenantId(...)`

4. `POST /vehicles`
- Creates vehicle for current tenant.
- Code path: `PoliceController.createVehicle()` -> `PatrolVehicleRepository.save(...)`

5. `GET /incidents`
- Returns incidents for current tenant.
- Code path: `PoliceController.getAllIncidents()` -> `IncidentRepository.findByTenantId(...)`

## `event-service` APIs

Base path: `/api/v1/telemetry`

1. `GET /device/{deviceId}`
- Returns one device snapshot from Redis.
- Code path:
  - `TelemetryController.getDeviceTelemetry()`
  - `TelemetrySnapshotService.getDeviceTelemetry()`
  - Redis key: `twin:snapshot:{deviceId}`

2. `GET /all`
- Returns all device snapshots from Redis.
- Code path:
  - `TelemetryController.getAllTelemetry()`
  - `TelemetrySnapshotService.getAllTelemetry()`
  - scans Redis keys `twin:snapshot:*`

3. `GET /count`
- Returns count of active snapshots.
- Code path:
  - `TelemetryController.getActiveDeviceCount()`
  - `TelemetrySnapshotService.getAllTelemetry().size()`

## `sim-service` APIs

- No public business REST endpoints are used for normal flow.
- Main behavior is scheduled producer task in `DeviceLoadSimulator`.

## `command-service` APIs

- No REST endpoints currently.

## End-to-End Event Flow

1. Telemetry generation (`sim-service`)
- `DeviceLoadSimulator.simulateTelemetry()` runs on schedule.
- Creates `PoliceTelemetry` payload for each simulated device.
- Publishes with key `deviceId` to Kafka topic `police-telemetry`.

2. Telemetry ingestion (`event-service`)
- `TelemetryConsumer.consume()` listens on `police-telemetry`.
- Deserializes message JSON -> `PoliceTelemetry`.
- Stores snapshot through `TelemetrySnapshotService.storeTelemetry()`.
- Redis key written: `twin:snapshot:{deviceId}`.

3. Telemetry query (`event-service` -> UI)
- UI calls `/api/v1/telemetry/all` and `/api/v1/telemetry/count`.
- Controller reads from Redis-backed snapshot service.
- Dashboard Live Map / Alerts render from this response.

## Request Flow (Tenant-Sensitive Domain APIs)

1. Client sends request to `device-service` with header `X-Tenant-Id`.
2. `TenantFilter` sets tenant into `TenantContext`.
3. Controller reads/writes using repositories with tenant-aware methods.
4. Response returns only tenant-scoped data.
5. `TenantContext` is cleared at end of request.

## Key Files (Reference)

1. `device-service/src/main/java/com/police/iot/device/controller/PoliceController.java`
2. `event-service/src/main/java/com/police/iot/event/controller/TelemetryController.java`
3. `event-service/src/main/java/com/police/iot/event/kafka/TelemetryConsumer.java`
4. `event-service/src/main/java/com/police/iot/event/service/TelemetrySnapshotService.java`
5. `sim-service/src/main/java/com/police/iot/sim/simulator/DeviceLoadSimulator.java`
6. `common/src/main/java/com/police/iot/common/security/TenantFilter.java`
