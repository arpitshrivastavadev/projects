## 1. Overview
Phase 1 was introduced to make `event-service` Kafka consumption resilient instead of “best effort”. The previous Redis write path had fallback behavior that logged failures, but that alone did not guarantee controlled retry or dead-letter isolation.

The practical need was:
- avoid losing failed events silently,
- separate transient failures from poison messages,
- and make failure handling observable and operable.

The key gap in the previous pattern was **swallow + log** at fallback boundaries (notably Redis write fallback), which can hide failures from Kafka error handling when exceptions do not propagate.

## 2. Before Phase 1
Original effective flow in this repo:

`sim-service` → Kafka (`police-telemetry`) → `event-service` consumer → Redis digital twin (`TelemetrySnapshotService`)

Issues before retry/DLQ hardening:
- No explicit topic-level retry chain for consumer processing failures.
- No dedicated DLQ isolation path.
- Failures could be logged but not always surfaced to Kafka error handling if swallowed in fallback logic.

## 3. Phase 1 Design
### Topics used
- `police-telemetry` (main)
- `police-telemetry-retry` (retry)
- `police-telemetry-dlq` (DLQ)

### Flow
`main topic` → retries exhausted → `retry topic` → retries exhausted → `DLQ`

### Mechanism
`KafkaConsumerErrorHandlingConfig` wires two listener container factories:
- main consumer factory with `mainTopicErrorHandler()`
- retry consumer factory with `retryTopicErrorHandler()`

Both use Spring Kafka `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` to route failed records to the next topic.

## 4. Key Code Changes
Phase 1 behavior is visible through these code paths:

- **Consumer rethrows on failure**
  - `TelemetryConsumer` catches processing exceptions, logs structured context, increments failure metric, then throws `RuntimeException` so Kafka error handler can route/retry.

- **Retry + DLQ routing added**
  - `KafkaConsumerErrorHandlingConfig` main handler routes to `police-telemetry-retry`.
  - Retry-topic handler routes to `police-telemetry-dlq`.

- **Structured failure/routing logging**
  - includes topic, partition, offset, key, and exception details.
  - consumer logs include correlation id extraction from `X-Correlation-ID` header.

- **Metrics added**
  - consumer success/failure counters,
  - retry-attempt counters per topic/attempt,
  - routed-failure counters with `path` and destination tags.

## 5. Behavior After Change
- **Transient failure** (e.g., first attempt fails, later succeeds): event is retried and can recover without manual intervention.
- **Persistent failure**: record is eventually routed through retry topic and ends in DLQ.
- **Poison message isolation**: malformed/unprocessable records are quarantined in DLQ instead of repeatedly impacting normal processing.

## 6. Important Nuance
A critical nuance in current repo behavior:

- `TelemetrySnapshotService.storeTelemetry` uses Resilience4j fallback (`storeTelemetryFallback`) that logs and does not rethrow.
- Because Kafka retry/DLQ requires exception propagation from listener processing, Redis write failures that are fully swallowed in fallback do **not** trigger Kafka retry/DLQ.
- Therefore, only exceptions that escape consumer processing reach the Spring Kafka error handler chain.

## 7. Observability
Observed/implemented observability in `event-service`:

- Kafka consumption metrics:
  - `event.kafka.consume.success`
  - `event.kafka.consume.failures`
  - `event.kafka.consume.retry.attempts`
  - `event.kafka.consume.failures.routed`
- Redis fallback metric:
  - `event.redis.operation.failures`
- Actuator + Prometheus are enabled (`health,info,prometheus,metrics`) for scraping and dashboards.

## 8. Trade-offs
Phase 1 trade-offs in this repo:
- Additional Kafka topics and operational overhead.
- Eventual consistency: failed events may be applied later after retry.
- DLQ is only useful with a replay/remediation process (not yet automated in this module).
- Swallowed internal fallbacks can bypass topic retry unless failures are propagated.

## 9. Interview Explanation
“In Phase 1, we moved from basic consume-and-log to a production retry pipeline in `event-service`. We used Spring Kafka `DefaultErrorHandler` with `DeadLetterPublishingRecoverer` to route records from `police-telemetry` to `police-telemetry-retry` and then to `police-telemetry-dlq`. The consumer intentionally rethrows processing errors so Kafka can apply the policy. We also added structured logging and Micrometer metrics for retries and routed failures. One important caveat is Redis fallback methods that swallow exceptions; those won’t trigger Kafka retry unless the exception is propagated.”

## 10. Interview Questions
- Why use Spring Kafka error handlers instead of manual custom retry loops?
- How do you define retryable vs non-retryable exceptions for telemetry ingestion?
- Why is DLQ necessary when retry already exists?
- What is still missing operationally? (e.g., DLQ replay tooling, alert thresholds, fallback strategy alignment)

## 11. Validation Summary
Based on existing tests and behavior in this repo:
- Happy path consumption updates snapshots successfully.
- Transient failures were verified to recover via retry path.
- Persistent/poison failures were verified to route to DLQ.
- Retry/DLQ metrics and structured routing logs are present.
- Redis fallback-swallow nuance remains and must be understood during validation.
