# Kafka Trace Propagation Fix (Phase 7)

## 1) Problem Description

### What we observed
Telemetry ingestion and processing were visible in tracing, but as **two different traces** instead of one:

- `device-service` created an HTTP trace for `POST /api/v1/telemetry`.
- `event-service` created Kafka consumer + Redis spans.
- The trace IDs were different, so the asynchronous hop over Kafka was not stitched into one end-to-end trace.

### Evidence pattern
- `device-service` span traceId: `A`
- `event-service` consumer/Redis traceId: `B`
- `A != B` (split traces)

---

## 2) Root Cause Analysis

The split trace came from **two combined issues**:

1. **Tracer bridge conflict (Brave + OpenTelemetry)**
   - `common` module pulled `micrometer-tracing-bridge-brave`.
   - Service modules also pulled `micrometer-tracing-bridge-otel`.
   - This can lead to competing propagator/tracer auto-configuration at startup and non-deterministic propagation behavior.

2. **Custom Kafka bean configuration bypassed Boot’s observation-friendly path**
   - A shared `KafkaTracingConfig` manually created `ProducerFactory`, `KafkaTemplate`, and `ConsumerFactory`.
   - The producer `KafkaTemplate` was created manually and not explicitly observation-enabled.
   - As a result, producer-side trace header injection for Kafka was not reliably applied.

On the consumer side, listener observation was already enabled in `event-service` custom listener container factories, so extraction could work once producer injection and propagator consistency were fixed.

---

## 3) Why this is important

In asynchronous systems (Kafka, queues), traces do **not** continue automatically like synchronous method calls unless context propagation is correctly configured.

- **Logs** can share correlation IDs but do not give timing/causality tree.
- **Distributed traces** show parent/child/span relationships across services and transport boundaries.
- Without propagation, debugging latency/errors across services becomes much harder.

---

## 4) Fix Implemented

### A. Removed Brave conflict and standardized on OpenTelemetry
- Removed `micrometer-tracing-bridge-brave` from `common/build.gradle`.
- Kept service-level OpenTelemetry bridge (`micrometer-tracing-bridge-otel`).
- Set propagation format explicitly to W3C in all three services:
  - `management.tracing.propagation.type: w3c`

### B. Removed custom shared Kafka tracing config that shadowed Boot auto-config
- Deleted `common/.../KafkaTracingConfig.java`.
- Let Spring Boot Kafka auto-configuration create core Kafka beans using `spring.kafka.*` properties.

### C. Producer tracing fix in `device-service`
- Explicitly enabled observation on the injected `KafkaTemplate`:
  - `kafkaTemplate.setObservationEnabled(true)` in `TelemetryPublishService`.
- Existing correlation ID header behavior remains unchanged.

### D. Consumer tracing fix in `event-service`
- Existing listener container factories already set:
  - `factory.getContainerProperties().setObservationEnabled(true)`
- With producer header injection + OTel-only propagator config, consumer now extracts trace context and continues same trace.

---

## 5) Before vs After

### BEFORE
`device-service` HTTP trace and `event-service` Kafka/Redis trace were disconnected.

### AFTER
Single trace across async boundary:
- HTTP span (`device-service`)
- Kafka producer span (`device-service`)
- Kafka consumer span (`event-service`)
- Redis span (`event-service`)

---

## 6) Final Trace Flow

```text
device-service HTTP /api/v1/telemetry
   → Kafka producer send (police-telemetry)
      → event-service Kafka consumer
         → Redis snapshot/idempotency write span
```

---

## 7) How to Test

1. Start observability stack (Jaeger + OTLP collector endpoint configured).
2. Start `device-service` and `event-service` with this configuration.
3. Call telemetry endpoint:

```bash
curl -X POST http://localhost:8081/api/v1/telemetry \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token-if-enabled>" \
  -H "X-Tenant-ID: tenant-a" \
  -H "X-Correlation-ID: corr-123" \
  -d '{
    "eventId":"evt-1001",
    "tenantId":"tenant-a",
    "deviceId":"veh-1",
    "officerId":"off-9",
    "speed":88,
    "status":"ON_PATROL",
    "timestamp":"2026-04-22T00:00:00Z"
  }'
```

4. Open Jaeger and search recent traces for `device-service`.
5. Verify one trace contains spans from both services, including Kafka producer/consumer and Redis operations.
6. Optional: verify logs still contain same `X-Correlation-ID` value end-to-end.

---

## 8) Interview Explanation (short, crisp)

> We had split traces because Kafka context propagation was inconsistent: our shared module pulled Brave while services used OpenTelemetry, and we also bypassed Boot’s Kafka auto-config with manual beans where producer observation wasn’t reliably enabled. We fixed it by standardizing on OTel + W3C propagation, removing the conflicting Brave bridge and shared manual Kafka tracing config, and ensuring Kafka producer/consumer observation is enabled. After that, Jaeger showed one trace from HTTP in `device-service` through Kafka to `event-service` Redis spans.

---

## 9) Key Learnings

- Async tracing requires explicit propagation across broker headers.
- Mixed tracer bridges (Brave + OTel) can break or fragment tracing.
- Custom infra beans can accidentally bypass framework observability defaults.
- Correlation IDs are useful for logs, but full traces require proper context propagation.
