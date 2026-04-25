# Interview Master Notes (Senior Java/Spring Boot)

This is a final, interview-focused synthesis of the repository (`common`, `device-service`, `event-service`, `sim-service`, `command-service`) and all generated analysis docs.

---

## 1) 2-minute project explanation

"This project is a multi-service Police IoT platform built on Spring Boot. `sim-service` and `device-service` produce telemetry to Kafka. `event-service` consumes telemetry, deduplicates with Redis markers, applies stale-event protection, and stores latest per-device digital twin snapshots in Redis. `device-service` and `command-service` persist relational data to PostgreSQL.

Cross-cutting concerns include tenant isolation (`TenantContext` + tenant filters), request correlation (`X-Correlation-ID` + MDC), and observability via Micrometer, Actuator, Prometheus, and OpenTelemetry tracing. Reliability patterns include request idempotency with AOP + Postgres ledger, Kafka retry topic and DLQ, and Redis fallback metrics. `command-service` is a tenant-scoped command state machine with status history and sync transitions, ready for future async dispatch evolution."

---

## 2) 5-minute detailed project explanation

1. **Module structure**
   - `common`: shared DTOs (`PoliceTelemetry`), filters, tenant/context classes, Kafka topic config.
   - `device-service`: police domain APIs (officers/vehicles/incidents), telemetry publish API, DB-backed write idempotency.
   - `event-service`: Kafka telemetry consumer, idempotency claim in Redis, stale ordering check, Redis digital twin read APIs.
   - `sim-service`: scheduled high-volume telemetry producer.
   - `command-service`: command create/get/ack lifecycle with durable state + history.

2. **Core runtime flow**
   - Producer (`sim` or `device`) sends telemetry keyed by `deviceId` to `police-telemetry`.
   - Consumer (`event-service`) does:
     - JSON deserialize,
     - Redis idempotency marker claim (`SETNX + TTL`),
     - ordered write (`storeTelemetryIfNewer`) to Redis snapshot,
     - retries + DLQ on failures.

3. **Tenant/security flow**
   - Authenticated tenant is extracted from JWT claims.
   - Tenant filters set `TenantContext` in `ThreadLocal` and clear in `finally`.
   - Header `X-Tenant-Id` is optional consistency check; mismatch rejected.

4. **Data model**
   - PostgreSQL: durable command + domain data.
   - Redis: latest-state digital twin + idempotency markers.
   - Kafka: streaming transport with retry and DLQ topics.

5. **Operational hardening present**
   - Metrics counters for command outcomes, Kafka failures/retries, Redis fallbacks, stale/duplicate telemetry.
   - Actuator/Prometheus exposure and OTLP tracing.

6. **Known gaps**
   - command dispatch is synchronous placeholder (`CREATED -> SENT`) without real async transport yet.
   - no DB-level RLS; tenant isolation is app-layer.
   - snapshot/index Redis writes are non-atomic multi-key operations.

---

## 3) Architecture explanation

### Architectural style
- Event-driven microservices with explicit infra boundaries.
- CQRS-like split in telemetry plane:
  - write events via Kafka,
  - read latest state from Redis projection.

### Why this structure works
- Kafka absorbs burst telemetry and decouples producers/consumers.
- Redis gives low-latency latest-state reads.
- Postgres holds durable business state and audit history.

---

## 4) Data plane vs control plane explanation

### Data plane (high-throughput telemetry)
- Producers: `sim-service`, `device-service` telemetry endpoint.
- Transport: Kafka topics (`police-telemetry`, retry, DLQ).
- Processing: `event-service` consumer, idempotency, stale check.
- Serving: Redis digital twin APIs.

### Control plane (business commands/domain APIs)
- `device-service` CRUD-style police APIs.
- `command-service` command lifecycle APIs and transitions.
- Persistence: PostgreSQL with Flyway migrations.

---

## 5) Filter and multitenancy explanation

- `CorrelationIdFilter` sets/propagates `X-Correlation-ID` and MDC context.
- `TenantFilter` / `CommandTenantFilter`:
  - run on protected API paths,
  - resolve authenticated tenant claim,
  - compare optional tenant header,
  - set/clear `TenantContext` around request.
- Service and repository logic uses `TenantContext` and tenant-scoped queries.

Interview angle: app-layer tenant isolation is implemented correctly for current scope, but DB-level RLS would be stronger defense-in-depth.

---

## 6) Idempotency explanation

### Request idempotency (device write APIs)
- `@IdempotentWrite` AOP intercepts POST writes.
- Requires `Idempotency-Key` header.
- Scope key: `tenant + route + method + key`.
- Stores request hash + response body/status in Postgres table.
- Same key + same payload => replay response.
- Same key + different payload => `409` conflict.

### Event idempotency (Kafka telemetry)
- Redis marker claim (`SETNX + TTL`) per deterministic key.
- Duplicate claim failure => skip event.
- Fail after claim => marker release + rethrow to trigger retry/DLQ.

---

## 7) Kafka retry/DLQ explanation

- Main listener consumes `police-telemetry`.
- `DefaultErrorHandler` with backoff retries locally.
- Exhausted main retries => route record to `police-telemetry-retry`.
- Retry listener consumes retry topic.
- Exhausted retry retries => route to `police-telemetry-dlq`.
- Poison messages are isolated in DLQ, not blocking hot path.

---

## 8) Redis digital twin explanation

- Snapshot key: `twin:snapshot:{deviceId}`.
- Device index set: `twin:snapshot:devices`.
- Update logic writes only if incoming timestamp is strictly newer.
- Stale reasons tracked: older, equal, missing incoming timestamp.
- Read APIs provide device/all/count views from snapshot store.

---

## 9) PostgreSQL usage explanation

- `device-service` stores officers/vehicles/incidents + idempotency records.
- `command-service` stores command current state + transition history.
- Flyway controls schema migrations.
- JPA uses validate mode (`ddl-auto=validate`) to enforce migration-first schema ownership.

---

## 10) Cache failure handling

- Redis read fallbacks return null/empty and increment failure metrics.
- Redis write ordered-update fallback throws runtime exception.
- This causes consumer failure propagation into Kafka retry/DLQ path.

---

## 11) Kafka failure handling

- Consumer processing exceptions increment failure counters and rethrow.
- Error handlers apply configured attempts/backoff.
- Routed-failure metrics/logs emitted when moving to retry or DLQ.

---

## 12) Redis failure handling

- Resilience4j circuit-breaker/retry/bulkhead/rate-limit wrappers on snapshot service operations.
- Fallbacks are operation-specific:
  - read fallbacks degrade gracefully,
  - critical ordered-write fallback throws to preserve reliability semantics.

---

## 13) Scaling to 10x traffic

1. Increase Kafka partitions (carefully with rebalancing cost).
2. Scale `event-service` consumers horizontally within same group.
3. Tune Redis (memory policy, clustering/sentinel, pipeline where safe).
4. Reduce hot partitions (monitor key skew on `deviceId`).
5. Optimize simulator/producer rate limits and executor sizing.
6. Introduce backpressure + admission control on ingest APIs.
7. Add async command dispatch (outbox + worker + ack events).
8. Add read API caching strategy and pagination for `all` endpoints.
9. Use load tests + SLO-driven tuning before peak rollout.
10. Define autoscaling triggers on lag + CPU + p95 latency.

---

## 14) UML explanation script (presentation-friendly)

"Start with the high-level diagram: four deployable services plus common shared module and Kafka/Redis/Postgres infrastructure. Then explain class diagrams by responsibility: device-service for domain writes and telemetry publishing, event-service for stream processing and Redis projection, command-service for state machine + history.

In sequence diagrams, first show request filter chain and tenant context lifecycle. Next show telemetry path: producer -> Kafka -> consumer -> idempotency -> stale-check -> Redis. Then show idempotent write API and command lifecycle transitions. End with component diagram to tie APIs, filters, services, and infrastructure into one mental model."

---

## 15) 50 deep interview questions with answers

1. **Why split telemetry processing into event-service?**
   - To decouple ingest from projection/read concerns and scale consumers independently.
2. **Why Kafka over direct HTTP fan-out?**
   - Better buffering, decoupling, replay, and failure isolation.
3. **Why Redis for latest state?**
   - Low-latency key-based access and overwrite-friendly model.
4. **How is ordering handled per device?**
   - `deviceId` partition key + stale timestamp check before overwrite.
5. **What if messages arrive out of order?**
   - Older/equal snapshots are skipped.
6. **What if duplicate events arrive?**
   - Redis idempotency claim rejects duplicate processing.
7. **Why have both idempotency and stale checks?**
   - Duplicate suppression and ordering protection solve different failure classes.
8. **How do you avoid duplicate POST writes?**
   - AOP idempotent-write ledger with request hash comparison.
9. **How are idempotency races handled in HTTP layer?**
   - Unique DB index + DataIntegrityViolation fallback read/replay.
10. **What is the risk of timestamp fallback idempotency key?**
   - Collision for distinct events sharing tenant+device+timestamp.
11. **How does tenant isolation work?**
   - Filter-resolved claim + tenant-scoped queries + context cleanup.
12. **Header or JWT tenant: which is authoritative?**
   - Authenticated claim; header only consistency check.
13. **Why ThreadLocal TenantContext?**
   - Simple per-request context propagation in servlet model.
14. **ThreadLocal downside?**
   - Async propagation complexity; requires strict cleanup.
15. **How is cleanup guaranteed?**
   - `finally` blocks in tenant filters and correlation filter.
16. **How is command audit maintained?**
   - Separate history table append on each status change.
17. **Why current state table + history table?**
   - Efficient current reads + full transition timeline.
18. **Is command dispatch actually async today?**
   - No, current `SENT` transition is synchronous placeholder.
19. **How are ACK/NACK modeled?**
   - ACK endpoint with statuses `ACKED`, `FAILED`, `TIMED_OUT`.
20. **How are invalid transitions blocked?**
   - Service-level transition validation + `400` error.
21. **How are Kafka retries implemented?**
   - Spring `DefaultErrorHandler` + fixed backoff + retry topic routing.
22. **How are poison messages isolated?**
   - Retry exhaustion routes to DLQ.
23. **What is DLQ operationally missing?**
   - Automated replay/remediation pipeline.
24. **How is correlation done across services?**
   - Correlation ID in HTTP/Kafka headers + MDC logging.
25. **How is distributed tracing enabled?**
   - Micrometer tracing bridge + OTLP endpoint + observation-enabled Kafka.
26. **Which key metrics matter most?**
   - consume success/failure, retry routed, redis fallback, command outcomes.
27. **How do you monitor consumer lag here?**
   - Need external Kafka exporter/JMX lag metrics.
28. **How compute p95/p99 latency?**
   - Prometheus `histogram_quantile` on `http_server_requests_seconds_bucket`.
29. **What are readiness signals?**
   - DB readiness for relational services, Redis readiness for event-service.
30. **How does Redis failure affect read APIs?**
   - Fallback null/empty responses.
31. **How does Redis failure affect consumer writes?**
   - Exception propagation triggers retry/DLQ.
32. **What consistency risk exists in Redis projection?**
   - Non-atomic snapshot write + index set update.
33. **How would you harden that?**
   - Lua script/transaction for atomic multi-key update.
34. **How are logs structured per environment?**
   - Plain console dev; JSON console profile for prod in shared logback.
35. **What security gap is notable?**
   - Dev JWT path must never be enabled in production.
36. **How to harden JWT in prod?**
   - OAuth2 resource server + JWKS rotation + aud/iss enforcement.
37. **Is DB-level tenant isolation present?**
   - No; app-layer only.
38. **How to add stronger tenant enforcement?**
   - PostgreSQL RLS policies with tenant context propagation.
39. **Why use Flyway + validate mode?**
   - Prevent schema drift and enforce migration ownership.
40. **How does simulator handle overload?**
   - Executor rejection with metric increment + drop warning.
41. **Can this support 10x throughput immediately?**
   - Partially; requires partition/consumer scaling and lag-based autoscaling.
42. **What are top hot spots at 10x?**
   - Kafka partition skew, Redis memory/latency, consumer lag, DB write contention.
43. **How to improve command reliability long-term?**
   - Outbox pattern + async dispatch + delivery retries and terminal guards.
44. **What would you alert on first?**
   - DLQ routed failures, lag growth, Redis fallbacks, 5xx and p95 spikes.
45. **How to explain stale-skip metric spikes?**
   - Potential producer clock skew/order regressions or delayed events.
46. **How to prevent unbounded idempotency storage?**
   - Cleanup policy/partitioning for DB ledger and tuned TTL for Redis markers.
47. **Is exactly-once guaranteed?**
   - No; design is idempotent-at-consumer-edge with at-least-once semantics.
48. **What interview story shows seniority here?**
   - Explain tradeoff between simplicity and correctness, plus concrete hardening roadmap.
49. **Which one change yields best reliability gain?**
   - End-to-end lag+DLQ observability with runbooks and replay automation.
50. **How would you summarize architecture quality?**
   - Solid modular baseline with practical resilience patterns, plus clear path to production-grade hardening.

---

## 16) Pros/tradeoffs for major design choices

| Design choice | Pros | Tradeoffs |
|---|---|---|
| Kafka event bus | decoupling, buffering, replay | operational complexity, lag monitoring required |
| Redis digital twin | low-latency latest-state reads | non-atomic multi-key update risks |
| Postgres for commands/domain | strong consistency, rich query/audit | higher write latency vs cache |
| App-layer tenant filtering | fast to implement, explicit logic | missing DB-enforced isolation |
| AOP request idempotency | reusable and transparent for endpoints | response replay schema/version drift risk |
| Redis event idempotency | fast duplicate suppression | TTL tuning and fallback-key collision risk |
| Retry topic + DLQ | poison isolation and controlled retries | needs replay tooling and ops ownership |
| Micrometer + Actuator | standardized instrumentation | must augment with lag/infra exporters |
| OTel tracing | cross-service causality | sampling/storage overhead |
| Sync command flow | simple deterministic behavior | no real dispatch guarantee yet |

---

## 17) Weak areas in current implementation

1. Command dispatch still placeholder (no async transport pipeline).
2. No DB RLS; tenant isolation relies on app discipline.
3. Redis snapshot/index updates are not atomic.
4. Snapshot keys currently have no lifecycle cleanup policy.
5. Kafka lag monitoring is not built-in yet.
6. DLQ replay/remediation is manual/unspecified.
7. Dev JWT filter risk if misconfigured in production.
8. Limited explicit SLO/histogram config for robust p95/p99 monitoring.

---

## 18) Best production improvements to mention in interview

1. Implement transactional outbox + async command dispatch + ACK event consumption.
2. Add PostgreSQL Row-Level Security for tenant defense-in-depth.
3. Make Redis snapshot write + index update atomic (Lua script).
4. Add snapshot retention/cleanup strategy (TTL + reaper/index repair jobs).
5. Add Kafka lag metrics exporter and lag-based autoscaling.
6. Add DLQ replay service with dedupe/guardrails and audit trail.
7. Enforce standard OAuth2 resource-server JWT validation in prod.
8. Define and alert on SLOs (error rate + p95/p99 + lag + fallback rates).
9. Standardize structured JSON logs across environments.
10. Publish runbooks for top incidents (lag spike, Redis outage, DLQ growth, tenant mismatch).

---

## Final interview tip

Keep your narrative in this order for senior rounds:
1. business problem + service boundaries,
2. happy-path runtime flow,
3. failure handling and observability,
4. tradeoffs and known weaknesses,
5. concrete production hardening roadmap.

That framing demonstrates systems thinking, not just code reading.
