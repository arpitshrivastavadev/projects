# Kafka Trace Propagation Final Fix

## What Was Still Broken

After the first Phase 7 rollout, the Kafka hop was only partially fixed:

- Kafka records carried a `traceparent` header.
- `device-service` and `event-service` logs could show the same trace ID.
- But startup had previously failed with:
  - `expected single matching bean but found 2: bravePropagator, otelPropagator`
- And earlier Jaeger runs showed split topology:
  - HTTP span in `device-service`
  - separate consumer + Redis trace in `event-service`
  - no producer span in the same trace

That meant transport propagation had improved, but tracing was not yet cleanly standardized or fully proven in Jaeger.

## Why `traceparent` + Same Log Trace ID Was Not Enough

`traceparent` in Kafka headers only proves context injection happened on the message.

Matching log trace IDs only prove MDC/context was extracted into logs.

Neither proves that Jaeger received one single causal trace with the expected span tree:

```text
HTTP /api/v1/telemetry
  -> Kafka producer span
     -> police-telemetry receive
        -> event.redis.store_if_newer
```

The real success criterion is one Jaeger trace ID containing all four stages with correct parent-child references.

## Exact Source Of The Brave Conflict

The conflict came from the shared `common` module historically exporting Brave:

- `common/build.gradle`
  - previously included `io.micrometer:micrometer-tracing-bridge-brave`

That put Brave classes on every service runtime classpath while the service modules also declared:

- `io.micrometer:micrometer-tracing-bridge-otel`
- `io.opentelemetry:opentelemetry-exporter-otlp`

With both tracer bridges available, Spring Boot tracing auto-configuration could create both:

- `bravePropagator`
- `otelPropagator`

That was the direct cause of the duplicate propagator startup failure.

## Exact Fix Applied

The code fix already present in commit `25fb005` standardized tracing on OpenTelemetry only.

### 1. Remove Brave from shared runtime

Changed:

- [`common/build.gradle`](/Users/arpitshrivastava/Documents/code/Projects/projects/Police112/demo1/common/build.gradle)

Fix:

- removed `io.micrometer:micrometer-tracing-bridge-brave`

Result:

- Brave is no longer exported transitively from `common`
- services can start normally without `--spring.autoconfigure.exclude=...BraveAutoConfiguration`

### 2. Remove manual shared Kafka bean config

Removed:

- `common/src/main/java/com/police/iot/common/config/kafka/KafkaTracingConfig.java`

Why it mattered:

- it manually created `ProducerFactory`, `KafkaTemplate`, and `ConsumerFactory`
- that bypassed Spring Boot’s normal Kafka auto-configuration path
- the manually created producer template did not explicitly enable observation

Result:

- Spring Boot now owns the base Kafka bean wiring
- service-level `spring.kafka.*` tracing settings apply cleanly

### 3. Make W3C propagation explicit

Changed:

- [`device-service/src/main/resources/application.yaml`](/Users/arpitshrivastava/Documents/code/Projects/projects/Police112/demo1/device-service/src/main/resources/application.yaml)
- [`event-service/src/main/resources/application.yaml`](/Users/arpitshrivastava/Documents/code/Projects/projects/Police112/demo1/event-service/src/main/resources/application.yaml)
- [`command-service/src/main/resources/application.yaml`](/Users/arpitshrivastava/Documents/code/Projects/projects/Police112/demo1/command-service/src/main/resources/application.yaml)

Fix:

```yaml
management:
  tracing:
    propagation:
      type: w3c
```

### 4. Ensure producer observation is enabled

Changed:

- [`device-service/src/main/java/com/police/iot/device/service/TelemetryPublishService.java`](/Users/arpitshrivastava/Documents/code/Projects/projects/Police112/demo1/device-service/src/main/java/com/police/iot/device/service/TelemetryPublishService.java)

Fix:

- `kafkaTemplate.setObservationEnabled(true)` in `@PostConstruct`

Result:

- producer spans are exported, not just headers injected

### 5. Keep consumer observation enabled

Already present in:

- [`event-service/src/main/java/com/police/iot/event/config/KafkaConsumerErrorHandlingConfig.java`](/Users/arpitshrivastava/Documents/code/Projects/projects/Police112/demo1/event-service/src/main/java/com/police/iot/event/config/KafkaConsumerErrorHandlingConfig.java)

Relevant line:

- `factory.getContainerProperties().setObservationEnabled(true);`

Result:

- consumer span continues the propagated context
- Redis observation remains a child of the consumer span

## Before vs After

### Before

- startup could fail with duplicate propagators
- Kafka header propagation existed or partially existed
- Jaeger could still show split traces

### After

- `device-service`, `event-service`, and `command-service` start cleanly with normal `bootRun`
- no runtime exclusion flags are needed
- one end-to-end Jaeger trace exists across HTTP -> Kafka producer -> Kafka consumer -> Redis

## Final Verified Jaeger Trace Shape

Verified trace ID:

- `7bc8dad62cb0817a431135317611da84`

Observed spans in Jaeger:

- `http post /api/v1/telemetry`
- `police-telemetry send`
- `police-telemetry receive`
- `event.redis.store_if_newer`

Observed parent-child chain:

```text
http post /api/v1/telemetry
  -> police-telemetry send
     -> police-telemetry receive
        -> event.redis.store_if_newer
```

Verified Kafka header on the exact record:

```text
traceparent: 00-7bc8dad62cb0817a431135317611da84-7c2dc190503edffa-01
X-Correlation-ID: corr-trace-final-1
```

Event-service consumed the same event under the same trace ID:

- device log trace ID: `7bc8dad62cb0817a431135317611da84`
- event log trace ID: `7bc8dad62cb0817a431135317611da84`

## How To Test Locally

1. Start infra:

```bash
docker compose up -d postgres redis zookeeper kafka kafka-init
docker compose -f docs/phase-7-distributed-tracing/jaeger-docker-compose.yaml up -d
```

2. Start services normally, without any tracing exclusion flags:

```bash
env GRADLE_USER_HOME=/Users/arpitshrivastava/Documents/code/Projects/projects/Police112/demo1/.gradle-local ./gradlew :device-service:bootRun --args='--spring.profiles.active=dev --server.port=8081'
env GRADLE_USER_HOME=/Users/arpitshrivastava/Documents/code/Projects/projects/Police112/demo1/.gradle-local ./gradlew :event-service:bootRun --args='--spring.profiles.active=dev --server.port=8083'
env DB_NAME=police_command_db GRADLE_USER_HOME=/Users/arpitshrivastava/Documents/code/Projects/projects/Police112/demo1/.gradle-local ./gradlew :command-service:bootRun --args='--spring.profiles.active=dev --server.port=8082'
```

3. Send telemetry through `device-service`.

4. Confirm in Jaeger:

- service: `device-service`
- trace contains both `device-service` and `event-service`
- same trace ID for HTTP, producer, consumer, and Redis spans

5. Optional deep check:

- read the Kafka record with `print.headers=true`
- verify `traceparent` and `X-Correlation-ID`

## Interview-Ready Explanation

We originally had two tracing problems: Brave and OpenTelemetry were both available on the runtime classpath, and we also had a shared manual Kafka config that bypassed Spring Boot’s observation-friendly Kafka auto-config. That caused duplicate propagators at startup and incomplete Kafka span linkage. The fix was to remove the shared Brave bridge, delete the manual Kafka tracing config, standardize on OTel with W3C propagation, and explicitly enable Kafka producer observation. After that, Jaeger showed one true trace from the HTTP request in `device-service`, through the Kafka producer, into the `event-service` consumer, and down to the Redis digital twin write.

## Key Learnings

- Async tracing is not finished when headers exist; it is finished when one span tree exists in the backend.
- Shared modules should not export competing tracing bridges.
- Manual infrastructure beans can silently bypass framework observability defaults.
- Kafka producer and consumer observation both need to be active for full end-to-end topology.
