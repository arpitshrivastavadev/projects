# Police IoT Platform: Reliable Local Test Walkthrough

This guide is updated to avoid the issues seen earlier (missing JARs, missing tenant header, empty Redis twin keys).

## Prerequisites

- Docker + Docker Compose
- `curl`

Note: Java is not required on host for Docker-based run, because each service Dockerfile now builds its own JAR in a multi-stage build.

## 1. Start Full Stack

```bash
docker compose up -d --build
```

This starts:
- `postgres`, `redis`, `zookeeper`, `kafka`, `kafka-init`
- `device-service` (`8081`)
- `event-service` (`8083`)
- `sim-service` (`8084`)
- `command-service` (`8082`)

Check status:

```bash
docker compose ps
```

Expected:
- All containers are `Up`
- `postgres` is `healthy`

## 2. Verify Device APIs (Tenant-Aware)

Important: `device-service` endpoints require `X-Tenant-Id`.

Health:

```bash
curl -i http://localhost:8081/actuator/health \
  -H "X-Tenant-Id: NYPD"
```

Create officer:

```bash
curl -i -X POST http://localhost:8081/api/v1/police/officers \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: NYPD" \
  -d '{
    "userId": "jdoe",
    "name": "John Doe",
    "badgeNumber": "B123",
    "status": "ACTIVE"
  }'
```

Create vehicle:

```bash
curl -i -X POST http://localhost:8081/api/v1/police/vehicles \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: NYPD" \
  -d '{
    "plateNumber": "NY-P-9988",
    "vehicleType": "SUV",
    "edgeNodeId": "EDGE-1",
    "status": "AVAILABLE",
    "location": "40.7128,-74.0060"
  }'
```

Verify tenant isolation:

```bash
curl -sS http://localhost:8081/api/v1/police/officers -H "X-Tenant-Id: NYPD"
curl -sS http://localhost:8081/api/v1/police/officers -H "X-Tenant-Id: LAPD"
```

Expected:
- NYPD returns created officer(s)
- LAPD returns `[]`

## 3. Verify Real-Time Telemetry (Digital Twin in Redis)

Find Redis container:

```bash
docker ps --format 'table {{.Names}}\t{{.Image}}'
```

Usually: `demo1-redis-1`

Check twin snapshot keys:

```bash
docker exec demo1-redis-1 redis-cli KEYS "twin:snapshot:*"
```

Check one sample snapshot:

```bash
docker exec demo1-redis-1 redis-cli GET "twin:snapshot:POLICE-DEV-0"
```

Expected:
- Multiple `twin:snapshot:*` keys
- JSON payload for `POLICE-DEV-0`

## 4. Verify Prometheus Metrics

The previous grep for `telemetry` can be empty depending on metric names. Use Kafka consumer metrics:

```bash
curl -sS http://localhost:8083/actuator/prometheus | grep kafka_consumer
```

Expected:
- Many `kafka_consumer_*` metrics are returned.

## 5. Common Troubleshooting

1. `curl: (7) Failed to connect to localhost port 8081`
   Ensure stack is up: `docker compose up -d --build`.
2. `X-Tenant-Id header is missing`
   Add `-H "X-Tenant-Id: <TENANT>"` to `device-service` requests.
3. No Redis twin keys
   Ensure both `event-service` and `sim-service` are `Up`, then wait 10-20 seconds and retry Redis commands.
