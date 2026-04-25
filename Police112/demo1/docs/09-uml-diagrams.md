# UML Diagrams for Interview Presentation

This file contains interview-friendly Mermaid UML/architecture diagrams for the repository.

---

## 1) High-level architecture diagram

```mermaid
flowchart LR
    UI[Client / Dashboard]

    subgraph SVC[Spring Boot Services]
        DEV[device-service]
        CMD[command-service]
        EVT[event-service]
        SIM[sim-service]
    end

    subgraph SHARED[Shared Module]
        COM[common module\nDTOs, filters, security, kafka config]
    end

    subgraph INFRA[Infrastructure]
        K[(Kafka)]
        R[(Redis)]
        P[(PostgreSQL)]
    end

    UI --> DEV
    UI --> CMD
    UI --> EVT

    SIM --> K
    DEV --> K
    K --> EVT

    EVT --> R
    DEV --> P
    CMD --> P

    COM -. used by .- DEV
    COM -. used by .- CMD
    COM -. used by .- EVT
    COM -. used by .- SIM
```

**Explanation:** Shows service boundaries, shared `common` module usage, and core runtime integrations with Kafka, Redis, and PostgreSQL.

---

## 2) Class diagram for `common` module

```mermaid
classDiagram
    class PoliceTelemetry {
      +String eventId
      +String deviceId
      +String tenantId
      +Instant timestamp
      +Double latitude
      +Double longitude
      +Double speed
      +Integer batteryLevel
      +String officerId
      +String vehicleId
      +String status
    }

    class BaseEntity {
      +Instant createdAt
      +Instant updatedAt
      +String createdBy
      +String updatedBy
    }

    class TenantContext {
      +setTenantId(String)
      +getTenantId() String
      +clear()
    }

    class TenantFilter {
      +doFilter(...)
    }

    class CorrelationIdFilter {
      +doFilter(...)
    }

    class RequestLoggingFilter {
      +doFilter(...)
    }

    class AuthenticatedTenantResolver {
      +resolveTenantId(Authentication) Optional~String~
    }

    class KafkaConfig {
      +telemetryTopic() NewTopic
      +telemetryRetryTopic() NewTopic
      +telemetryDlqTopic() NewTopic
    }

    TenantFilter --> TenantContext
    TenantFilter --> AuthenticatedTenantResolver
    CorrelationIdFilter --> TenantFilter : reads tenant header constant
```

**Explanation:** Highlights shared DTOs, security/filter primitives, and topic declaration config reused by multiple services.

---

## 3) Class diagram for `device-service`

```mermaid
classDiagram
    class DeviceServiceApplication

    class PoliceController {
      +getAllOfficers() List~Officer~
      +createOfficer(Officer) Officer
      +getAllVehicles() List~PatrolVehicle~
      +createVehicle(PatrolVehicle) PatrolVehicle
      +getAllIncidents() List~Incident~
    }

    class TelemetryPublishController {
      +publish(PoliceTelemetry) ResponseEntity
    }

    class TelemetryPublishService {
      +publish(PoliceTelemetry)
    }

    class IdempotentWrite <<annotation>>

    class IdempotencyAspect {
      +enforceIdempotency(...)
    }

    class IdempotencyRecord
    class IdempotencyRecordRepository

    class OfficerRepository
    class PatrolVehicleRepository
    class IncidentRepository

    class SecurityConfig
    class DevJwtAuthenticationFilter

    PoliceController --> OfficerRepository
    PoliceController --> PatrolVehicleRepository
    PoliceController --> IncidentRepository
    PoliceController ..> IdempotentWrite
    IdempotentWrite ..> IdempotencyAspect
    IdempotencyAspect --> IdempotencyRecordRepository
    IdempotencyRecordRepository --> IdempotencyRecord

    TelemetryPublishController --> TelemetryPublishService
    SecurityConfig --> DevJwtAuthenticationFilter
```

**Explanation:** Emphasizes CRUD-style police APIs, telemetry producer path, and AOP-based request idempotency.

---

## 4) Class diagram for `event-service`

```mermaid
classDiagram
    class EventServiceApplication

    class TelemetryController {
      +getDeviceTelemetry(String) PoliceTelemetry
      +getAllTelemetry() List~PoliceTelemetry~
      +getActiveDeviceCount() long
    }

    class TelemetryConsumer {
      +consumeMain(ConsumerRecord, String)
      +consumeRetry(ConsumerRecord, String)
    }

    class TelemetrySnapshotService {
      +getDeviceTelemetry(String) PoliceTelemetry
      +getAllTelemetry() List~PoliceTelemetry~
      +storeTelemetryIfNewer(PoliceTelemetry) SnapshotStoreResult
    }

    class TelemetryEventIdempotencyService {
      +buildIdempotencyKey(PoliceTelemetry) String
      +claim(String) boolean
      +release(String)
    }

    class RedisConfig
    class KafkaConsumerErrorHandlingConfig
    class SecurityConfig

    TelemetryController --> TelemetrySnapshotService
    TelemetryConsumer --> TelemetrySnapshotService
    TelemetryConsumer --> TelemetryEventIdempotencyService
    KafkaConsumerErrorHandlingConfig --> TelemetryConsumer : listener factories
    RedisConfig --> TelemetrySnapshotService : RedisTemplate bean
```

**Explanation:** Shows event-service as Kafka consumer + Redis projection service + telemetry read API.

---

## 5) Class diagram for `command-service`

```mermaid
classDiagram
    class CommandController {
      +create(CreateCommandRequest) CommandResponse
      +getById(String) CommandResponse
      +acknowledge(String, CommandAckRequest) CommandResponse
    }

    class CommandService {
      +createCommand(CreateCommandRequest) CommandResponse
      +getCommand(String) CommandResponse
      +acknowledgeCommand(String, CommandAckRequest) CommandResponse
    }

    class CommandEntity {
      +String commandId
      +String tenantId
      +String targetDeviceId
      +String commandType
      +String payload
      +CommandStatus status
    }

    class CommandStatusHistoryEntity {
      +String historyId
      +String commandId
      +String tenantId
      +CommandStatus fromStatus
      +CommandStatus toStatus
      +String reason
      +Instant changedAt
    }

    class CommandRepository
    class CommandStatusHistoryRepository
    class CommandTenantFilter
    class CommandExceptionHandler

    CommandController --> CommandService
    CommandService --> CommandRepository
    CommandService --> CommandStatusHistoryRepository
    CommandRepository --> CommandEntity
    CommandStatusHistoryRepository --> CommandStatusHistoryEntity
    CommandExceptionHandler --> CommandController
    CommandTenantFilter --> CommandService : sets TenantContext used by service
```

**Explanation:** Captures command state machine persistence model (current state + transition history) and tenant-filtered API path.

---

## 6) Sequence diagram: request flow with filters and tenant context

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant CF as CorrelationIdFilter
    participant RF as RequestLoggingFilter
    participant SF as SecurityFilterChain
    participant TF as TenantFilter/CommandTenantFilter
    participant TC as TenantContext
    participant CTL as Controller
    participant SVC as Service
    participant REP as Repository

    C->>CF: HTTP request (Authorization, optional X-Correlation-ID)
    CF->>CF: generate/read correlationId + MDC set
    CF->>RF: continue
    RF->>SF: continue
    SF->>TF: after auth
    TF->>TC: setTenantId(authenticated tenant)
    TF->>CTL: pass request
    CTL->>SVC: business call
    SVC->>TC: read tenantId
    SVC->>REP: tenant-scoped query/write
    REP-->>SVC: data
    SVC-->>CTL: response
    CTL-->>C: HTTP response
    TF->>TC: clear()
    CF->>CF: MDC clear
```

**Explanation:** Demonstrates filter ordering and how tenant/correlation context flows into business logic and is cleaned up safely.

---

## 7) Sequence diagram: telemetry publish-consume-cache flow

```mermaid
sequenceDiagram
    autonumber
    participant API as device-service API
    participant PUB as TelemetryPublishService
    participant K as Kafka
    participant CON as TelemetryConsumer
    participant IDEM as TelemetryEventIdempotencyService
    participant SNAP as TelemetrySnapshotService
    participant R as Redis

    API->>PUB: POST /api/v1/telemetry
    PUB->>K: send(topic=police-telemetry, key=deviceId, payload)

    K->>CON: consumeMain(record)
    CON->>IDEM: buildIdempotencyKey + claim
    alt duplicate
        CON-->>K: skip
    else claimed
        CON->>SNAP: storeTelemetryIfNewer(telemetry)
        SNAP->>R: GET twin:snapshot:{deviceId}
        alt incoming newer
            SNAP->>R: SET twin:snapshot:{deviceId}
            SNAP->>R: SADD twin:snapshot:devices
        else stale/equal
            SNAP-->>CON: skip update
        end
        CON-->>K: success
    end
```

**Explanation:** Presents the main telemetry pipeline from API producer to Kafka consumer to Redis digital twin update.

---

## 8) Sequence diagram: idempotent write API (`@IdempotentWrite`)

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant Ctrl as PoliceController
    participant AOP as IdempotencyAspect
    participant Repo as IdempotencyRecordRepository
    participant DB as PostgreSQL
    participant Biz as Domain Repository

    Client->>Ctrl: POST /api/v1/police/officers + Idempotency-Key
    Ctrl->>AOP: method intercepted
    AOP->>Repo: findByTenant+Route+Method+Key

    alt existing record
        AOP->>AOP: compare request hash
        alt same hash + completed response
            AOP-->>Client: replay cached response
        else hash mismatch or in-progress
            AOP-->>Client: 409 conflict
        end
    else no record
        AOP->>Repo: saveAndFlush marker
        Repo->>DB: INSERT T_IDEMPOTENCY_RECORD
        AOP->>Biz: proceed business save
        Biz->>DB: INSERT business row
        AOP->>Repo: update marker with response
        Repo->>DB: UPDATE T_IDEMPOTENCY_RECORD
        AOP-->>Client: success
    end
```

**Explanation:** Shows optimistic race-safe request idempotency with DB unique scope and response replay behavior.

---

## 9) Sequence diagram: command lifecycle

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant Ctrl as CommandController
    participant Svc as CommandService
    participant CmdRepo as CommandRepository
    participant HistRepo as CommandStatusHistoryRepository
    participant DB as PostgreSQL

    Client->>Ctrl: POST /api/v1/commands
    Ctrl->>Svc: createCommand(request)
    Svc->>CmdRepo: save(status=CREATED)
    CmdRepo->>DB: INSERT device_commands
    Svc->>HistRepo: save(null->CREATED)
    HistRepo->>DB: INSERT command_status_history
    Svc->>CmdRepo: save(status=SENT)
    CmdRepo->>DB: UPDATE device_commands
    Svc->>HistRepo: save(CREATED->SENT)
    HistRepo->>DB: INSERT command_status_history
    Svc-->>Ctrl: CommandResponse(status=SENT)
    Ctrl-->>Client: 201

    Client->>Ctrl: POST /api/v1/commands/{id}/acks
    Ctrl->>Svc: acknowledgeCommand(id, status)
    Svc->>CmdRepo: findByCommandIdAndTenantId
    CmdRepo->>DB: SELECT
    Svc->>Svc: validate transition
    Svc->>CmdRepo: save(new status)
    CmdRepo->>DB: UPDATE
    Svc->>HistRepo: save(prev->new)
    HistRepo->>DB: INSERT history
    Ctrl-->>Client: 200
```

**Explanation:** Depicts synchronous command state progression and durable history append on each transition.

---

## 10) Component diagram: API, Kafka, Redis, PostgreSQL, filters, and services

```mermaid
flowchart TB
    subgraph ClientLayer
      U[Clients / UI]
    end

    subgraph DeviceService
      DAPI[REST Controllers]
      DF[Security + Tenant + Correlation Filters]
      DPUB[TelemetryPublishService]
      DIDEM[IdempotencyAspect]
    end

    subgraph CommandService
      CAPI[CommandController]
      CF[Security + CommandTenantFilter]
      CSV[CommandService]
    end

    subgraph EventService
      EAPI[TelemetryController]
      ECON[TelemetryConsumer]
      ESNAP[TelemetrySnapshotService]
      EIDEM[TelemetryEventIdempotencyService]
      EERR[Kafka Error Handling]
    end

    K[(Kafka)]
    R[(Redis)]
    P[(PostgreSQL)]

    U --> DAPI
    U --> CAPI
    U --> EAPI

    DAPI --> DF
    DAPI --> DIDEM
    DAPI --> DPUB

    CAPI --> CF
    CAPI --> CSV

    DPUB --> K
    K --> ECON
    ECON --> EIDEM
    ECON --> ESNAP
    ECON --> EERR

    ESNAP --> R
    EIDEM --> R

    DIDEM --> P
    CSV --> P
```

**Explanation:** Summarizes runtime components and dependencies in one interview slide-friendly diagram.
