## 1. Overview
Phase 3 adds stale-event / ordering protection in `event-service` so an older telemetry record cannot overwrite a newer Redis digital twin snapshot.

This phase builds on:
- **Phase 1:** main topic -> retry -> DLQ
- **Phase 2:** Redis idempotency (`SETNX` + TTL)

## 2. Problem Addressed
Kafka is at-least-once and does not guarantee perfect event-time order for consumers. A delayed record can arrive after a newer one for the same device.

Without ordering checks, a stale event may overwrite the latest device state in Redis.

## 3. Ordering Field Chosen
Ordering uses `PoliceTelemetry.timestamp` (`Instant`) because it is the smallest practical field already present in the payload and no sequence/version field exists today.

## 4. Comparison Rule
Before writing a snapshot for a device:
1. Read current snapshot from Redis.
2. Compare `incoming.timestamp` with `current.timestamp`.
3. **Write only if incoming timestamp is strictly newer** (`incoming > current`).
4. If incoming timestamp is older or equal, treat as stale and skip update.
5. If no current snapshot exists, process normally.

Conservative equal rule:
- `incoming == current` is considered stale and skipped.

## 5. Key Code Changes
### `TelemetrySnapshotService`
- Added `storeTelemetryIfNewer(PoliceTelemetry)`.
- Added `SnapshotStoreResult(updated, existingTimestamp)` return object.
- Added stale metric: `event.snapshot.stale.skipped` with reason tags:
  - `older`
  - `equal`
  - `missing_incoming_timestamp`

### `TelemetryConsumer`
- Replaced direct snapshot write call with ordered write method.
- Added stale skip log including:
  - `deviceId`
  - `tenantId`
  - incoming timestamp
  - current stored timestamp
  - `correlationId`
- Kept idempotency release-on-failure behavior unchanged.

## 6. Compatibility Notes
Phase 3 remains compatible with earlier phases:
- **Retry/DLQ:** unchanged, processing exceptions still propagate.
- **Idempotency:** unchanged key strategy and claim/release behavior.
- Stale skip is treated as safely processed (no exception, no retry trigger).

## 7. Tests Added/Updated
`TelemetrySnapshotServiceTest` covers:
1. newer event updates snapshot
2. older event skipped
3. equal timestamp skipped
4. no prior snapshot -> normal write

`TelemetryConsumerIdempotencyTest` covers:
5. stale skip behavior alongside idempotency claim/release logic

## 8. How to Run / Test
From repo root (`Police112/demo1`):

```bash
./gradlew :event-service:compileJava
./gradlew :event-service:test
```

If your environment blocks Maven Central, test execution may fail during dependency download; rerun in a network-enabled CI/dev environment.

## 9. Limitations and Follow-up
Timestamp-based ordering is simple and practical but has known limits:
- clock skew between producers
- same-timestamp collisions
- timestamp quality depends on source clocks

### Stronger long-term option
Introduce a per-device monotonic sequence/version generated at source and compare sequence first (timestamp as fallback for backward compatibility).
