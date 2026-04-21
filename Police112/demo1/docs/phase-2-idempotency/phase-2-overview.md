## 1. Overview
Phase 2 was added to prevent duplicate processing in `event-service`. Kafka provides at-least-once delivery semantics, so the same telemetry event can be delivered more than once (rebalance, retry, ack timing, or broker/client failures).

Without idempotency, duplicate deliveries can trigger repeated Redis digital twin writes and duplicated side effects.

## 2. Before Phase 2
Before idempotency guarding in consumer flow:
- each delivered record attempted processing,
- duplicate payloads could be written repeatedly to Redis,
- retry behavior increased duplicate-delivery probability under failure scenarios.

## 3. Phase 2 Design
Current Phase 2 implementation uses Redis marker claims:
- deterministic idempotency key per event,
- atomic claim via `SETNX`-style operation (`setIfAbsent`),
- TTL to automatically expire markers and bound storage.

If claim fails (already exists), event is treated as duplicate and skipped.

## 4. Idempotency Key Strategy
Key strategy in `TelemetryEventIdempotencyService`:
- **Primary:** `tenantId + deviceId + eventId`
- **Fallback:** `tenantId + deviceId + timestamp`

Fallback is necessary because upstream payloads may not always include `eventId`. In this repo, `PoliceTelemetry` now supports `eventId`, but fallback keeps compatibility with existing producers and older payloads.

## 5. Key Code Changes
Phase 2 implementation points:

- Added `TelemetryEventIdempotencyService`:
  - key generation
  - Redis claim (`setIfAbsent` + TTL)
  - marker release method
  - claim/release metrics

- Updated `TelemetryConsumer`:
  - build idempotency key immediately after deserialization,
  - claim marker before calling `TelemetrySnapshotService.storeTelemetry(...)`,
  - skip duplicate event safely,
  - increment duplicate metric,
  - release marker if processing fails after claim,
  - rethrow failure to preserve existing retry/DLQ behavior.

- Added config:
  - `police.event.idempotency.ttl` (default `PT24H`)

## 6. Processing Flow
1. Consume Kafka telemetry record.
2. Deserialize payload and generate idempotency key.
3. Attempt Redis claim (`SETNX` semantics via `setIfAbsent`).
4. If key already exists → log duplicate and skip processing.
5. If key is newly claimed → process event and update Redis snapshot.
6. If processing succeeds → keep marker until TTL expiry.
7. If processing fails after claim → release marker, rethrow exception, and let retry/DLQ continue.

## 7. Observability
Phase 2 observability additions:
- Metric: `event.kafka.consume.duplicates.skipped` (tagged by topic).
- Idempotency service counters:
  - `event.idempotency.claim` (claimed vs duplicate)
  - `event.idempotency.release` (deleted vs not_found)
- Duplicate log includes:
  - `deviceId`
  - `tenantId`
  - `idempotencyKey`
  - `correlationId`

## 8. Trade-offs
- Fallback key with timestamp can collide if two distinct events share same `tenantId + deviceId + timestamp`.
- TTL introduces memory-vs-safety tuning:
  - longer TTL = safer dedupe window, more Redis keys
  - shorter TTL = lower memory, higher late-duplicate risk
- This is idempotent-at-consumer-edge, not full Kafka exactly-once processing.

## 9. Limitations
Current limitations in this repo:
- No strict event ordering guarantee in idempotency logic.
- No sequence/version checks to prevent stale-out-of-order updates.
- No advanced dedupe store sharding/compaction strategy documented yet.

## 10. Interview Explanation
“In Phase 2, we added Redis-based idempotency in `event-service` to handle Kafka at-least-once duplicates. Each event gets a deterministic key—preferably `tenant+device+eventId`, otherwise `tenant+device+timestamp`. We claim the key atomically with Redis `SETNX` and TTL before snapshot write. If claim fails, we skip as duplicate and record a metric. If processing fails after claim, we delete the marker and rethrow so existing retry/DLQ flow still works. It’s simple, production-friendly, and easy to explain.”

## 11. Interview Questions
- Why not rely on Kafka exactly-once semantics here?
- Why choose Redis markers over DB uniqueness constraints?
- Why add TTL to dedupe markers?
- What happens if two different events share the same fallback timestamp key?

## 12. Validation Summary
From current tests and flow:
- first delivery processes normally,
- duplicate delivery is skipped,
- duplicate processing does not redundantly call snapshot update,
- marker release on failure is verified,
- retry/DLQ compatibility remains intact via exception rethrow after release.
