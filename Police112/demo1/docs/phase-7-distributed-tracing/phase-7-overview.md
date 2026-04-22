# Phase 7 - Distributed Tracing (OpenTelemetry)

## 1) What is distributed tracing?
Distributed tracing is a way to follow one request across multiple services as a single timeline (trace). Each service contributes spans (units of work), so we can debug cross-service latency and failures.

## 2) Why it is needed in this project
This project is a multi-service police IoT platform where one action crosses service boundaries:
- API request enters `device-service`.
- Telemetry is published to Kafka.
- `event-service` consumes the message and updates Redis digital twin.

Metrics and correlation IDs exist, but they do not show parent-child timing across HTTP, Kafka, and Redis in one place. Tracing closes that gap.

## 3) Logging vs Metrics vs Tracing
- **Logging**: detailed events/messages (good for exact context and errors).
- **Metrics**: numeric aggregates (good for alerts, dashboards, SLOs).
- **Tracing**: per-request end-to-end timeline across services (good for causality and latency breakdown).

Use all three together.

## 4) Tracing architecture in this system
- **Instrumentation standard**: Spring Boot 3 + Micrometer Tracing bridge for OpenTelemetry.
- **Exporter**: OTLP HTTP exporter to local Jaeger.
- **Services instrumented**:
  - `device-service`: incoming HTTP + Kafka producer span.
  - `event-service`: Kafka consumer span + Redis write spans.
  - `command-service`: incoming HTTP spans for create/get/ack APIs.
- **Trace context format**: W3C Trace Context (`traceparent`) with automatic propagation.

## 5) Exact trace flow (HTTP -> Kafka -> consumer -> Redis)
### Flow A: telemetry pipeline
1. Client calls `device-service` telemetry endpoint (`POST /api/v1/telemetry`).
2. `device-service` creates HTTP server span.
3. Telemetry is sent to Kafka (`police-telemetry`) with producer span.
4. Trace context is propagated in Kafka headers.
5. `event-service` consumes the record and continues the same trace (consumer span).
6. `event-service` writes digital twin data to Redis in child Redis spans.

### Flow B: command lifecycle
1. Client calls `command-service` create command API.
2. HTTP span captures controller/service/repository timing.
3. Client calls command acknowledge API (`/api/v1/commands/{id}/acks`) and sees trace for that request.

## 6) How trace propagation works
### HTTP propagation
- Incoming `traceparent` header is read automatically by tracing instrumentation.
- If absent, a new trace is started.
- Outgoing HTTP (if added later) would automatically carry trace headers.

### Kafka propagation
- Producer instrumentation injects trace context into Kafka headers.
- Consumer instrumentation extracts context and continues the same trace.
- Existing `X-Correlation-ID` header remains for log correlation and compatibility.

## 7) Tools used
- OpenTelemetry (via Micrometer bridge for Spring Boot)
- Jaeger (local trace backend and UI)
- OTLP/HTTP exporter (`http://localhost:4318/v1/traces`)

## 8) Step-by-step implementation summary
1. Added tracing dependencies (`micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`) in all three services.
2. Enabled tracing and OTLP exporter endpoint in each service's `application.yaml`.
3. Enabled Kafka observation for producer/consumer paths.
4. Added telemetry publish endpoint/service in `device-service` to produce Kafka events with existing correlation ID header.
5. Added explicit Redis observation spans in `event-service` digital twin write methods.
6. Kept log correlation format with `traceId`, `spanId`, and existing `correlationId`.

## 9) Configuration details
Minimal properties added per service:

```yaml
management:
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318/v1/traces}
```

Kafka observation (service-specific):

```yaml
spring:
  kafka:
    producer:
      observation-enabled: true
    consumer:
      observation-enabled: true
```

## 10) How to run Jaeger locally (Docker)
Use the compose file in this folder:

```bash
docker compose -f docs/phase-7-distributed-tracing/jaeger-docker-compose.yaml up -d
```

Then open:
- Jaeger UI: `http://localhost:16686`
- OTLP HTTP ingest: `http://localhost:4318/v1/traces`

## 11) How to test tracing end-to-end
1. Start Kafka, Redis, and all services.
2. Start Jaeger.
3. Send telemetry:

```bash
curl -X POST http://localhost:8081/api/v1/telemetry \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id:NYPD' \
  -H 'Authorization: Bearer <dev-jwt>' \
  -d '{
    "deviceId":"vehicle-101",
    "officerId":"officer-12",
    "vehicleId":"vehicle-101",
    "status":"ACTIVE",
    "speed":62.5,
    "batteryLevel":88,
    "latitude":40.7128,
    "longitude":-74.0060
  }'
```

4. In Jaeger, search by service `device-service` or by operation `POST /api/v1/telemetry`.
5. Confirm same trace contains Kafka producer -> event consumer -> Redis spans.

Command flow:
1. Call `POST /api/v1/commands` on command-service.
2. Call `POST /api/v1/commands/{id}/acks`.
3. Verify both requests produce command-service HTTP traces.

## 12) Sample trace explanation (what appears in UI)
Typical telemetry trace:
- Span 1: `http.server.requests` (`device-service`)
- Span 2: `spring.kafka.template` / producer send
- Span 3: `spring.kafka.listener` (`event-service` consumer)
- Span 4: `event.redis.store_if_newer` (custom Redis write observation)

If stale telemetry is detected, Redis write may be skipped and stale metric increments.

## 13) Interview explanation (short + crisp)
"We used Spring Boot's standard OpenTelemetry path via Micrometer Tracing. Incoming HTTP creates root spans, Kafka producer injects trace context into headers, Kafka consumer continues the trace in event-service, and Redis twin writes are child spans. Traces are exported over OTLP to Jaeger for end-to-end visibility."

## 14) Trade-offs and limitations
- Sampling is fixed at `1.0` for dev simplicity (higher overhead).
- Local Jaeger is for development, not hardened production storage.
- Only core flows instrumented (HTTP, Kafka, Redis write path) to keep implementation interview-friendly.

## 15) Future improvements
- Environment-based sampling policies.
- Add alert rules tied to span latency/error tags.
- Trace baggage strategy for tenant-aware diagnostics.
- Production-grade collector pipeline (OpenTelemetry Collector + long-term backend).
