# event-service

## Telemetry Kafka failure handling (retry + DLQ)

This service now uses a simple production-style failure flow for telemetry consumption:

1. **Main topic**: `police-telemetry`
2. **Retry topic**: `police-telemetry-retry`
3. **DLQ topic**: `police-telemetry-dlq`

### Flow

1. A telemetry event is consumed from `police-telemetry`.
2. If processing fails, the main listener retries locally (`police.kafka.retry.main.*`).
3. If still failing, the record is published to `police-telemetry-retry`.
4. Retry consumer processes from `police-telemetry-retry`.
5. If retry processing still fails after configured attempts (`police.kafka.retry.retry-topic.*`), the event is published to `police-telemetry-dlq`.

### What is sent to DLQ

- Original payload value (telemetry JSON)
- Kafka key
- Original metadata in headers (including exception/original topic metadata handled by Spring Kafka recoverer)
- Correlation header (`X-Correlation-ID`) when present

### Configuration knobs

`event-service/src/main/resources/application.yaml`

- `police.kafka.topics.telemetry`
- `police.kafka.topics.telemetry-retry`
- `police.kafka.topics.telemetry-dlq`
- `police.kafka.retry.main.attempts`
- `police.kafka.retry.main.backoff-ms`
- `police.kafka.retry.retry-topic.attempts`
- `police.kafka.retry.retry-topic.backoff-ms`

### Interview-friendly explanation

> We consume telemetry from the normal topic. If transient processing fails, we retry. If it still fails, we move the event to a retry topic. If retry processing is still unsuccessful, we route the record to a DLQ for later inspection/replay.
