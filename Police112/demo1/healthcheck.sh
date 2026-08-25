#!/usr/bin/env bash
set -euo pipefail

set -u
  cd /home/arpit-shrivastava/workspace/code/projects/Police112/demo1 || exit 1

  ok=1
  check_http() {
    local name=$1 url=$2
    if curl -fsS "$url" >/dev/null; then
      printf '%-20s : OK\n' "$name"
    else
      printf '%-20s : FAIL\n' "$name"
      ok=0
    fi
  }
  check_actuator() {
    local name=$1 port=$2 result
    result=$(curl -fsS "http://localhost:$port/actuator/health" 2>/dev/null | jq -r '.status // "DOWN"' 2>/dev/null || echo DOWN)
    printf '%-20s : %s\n' "$name" "$result"
    [[ $result == UP ]] || ok=0
  }

  echo 'Police112 Health'
  echo '================='

  running=$(docker compose ps --status running -q | wc -l)
  printf '%-20s : %s running containers\n' 'Docker stack' "$running"
  docker compose ps --format 'table {{.Service}}\t{{.State}}\t{{.Status}}'

  check_actuator command-service 8082
  check_actuator device-service 8081
  check_actuator event-service 8083
  check_actuator sim-service 8084

  if docker compose exec -T kafka kafka-broker-api-versions --bootstrap-server kafka:9092 >/dev/null 2>&1; then
    echo 'Kafka                : UP'
  else
    echo 'Kafka                : FAIL'; ok=0
  fi

  lag=$(curl -sSG --data-urlencode 'query=sum(kafka_consumer_fetch_manager_records_lag{job="event-service"})' \
    http://localhost:9090/api/v1/query | jq -r '.data.result[0].value[1] // "NO_DATA"' 2>/dev/null)
  echo "Kafka consumer lag   : $lag"

  if docker compose exec -T postgres pg_isready -U ecp_user -d police_db >/dev/null 2>&1; then
    echo 'PostgreSQL           : UP'
  else
    echo 'PostgreSQL           : FAIL'; ok=0
  fi

  if docker compose exec -T redis redis-cli ping 2>/dev/null | grep -qx PONG; then
    echo 'Redis                : UP'
  else
    echo 'Redis                : FAIL'; ok=0
  fi

  es=$(curl -fsS http://localhost:9200/_cluster/health 2>/dev/null | jq -r '.status // "DOWN"' 2>/dev/null || echo DOWN)
  echo "Elasticsearch        : ${es^^}"
  [[ $es == green || $es == yellow ]] || ok=0

  check_http Prometheus http://localhost:9090/-/ready

  targets=$(curl -fsS http://localhost:9090/api/v1/targets 2>/dev/null)
  up=$(printf '%s' "$targets" | jq '[.data.activeTargets[] | select(.health == "up")] | length' 2>/dev/null || echo 0)
  total=$(printf '%s' "$targets" | jq '.data.activeTargets | length' 2>/dev/null || echo 0)
  echo "Prometheus targets   : $up/$total UP"
  [[ $up == "$total" && $total != 0 ]] || ok=0

  check_http Grafana http://localhost:3000/api/health
  grafana_ds=$(curl -fsS -u admin:admin \
    http://localhost:3000/api/datasources/uid/PBFA97CFB590B2093/health 2>/dev/null |
    jq -r '.status // "FAIL"' 2>/dev/null || echo FAIL)
  echo "Grafana datasource   : $grafana_ds"
  [[ $grafana_ds == OK ]] || ok=0

  disk=$(df -P / | awk 'NR==2 {print $5}')
  mem=$(free | awk '/Mem:/ {printf "%.0f%%", $3*100/$2}')
  cpu=$(top -bn1 | awk -F'[, ]+' '/Cpu\(s\)/ {print 100-$8 "%"; exit}')
  echo "Disk                 : $disk"
  echo "Memory               : $mem"
  echo "CPU                  : $cpu"

  restart_issues=0
  for id in $(docker compose ps -aq); do
    state=$(docker inspect -f '{{.RestartCount}} {{.State.OOMKilled}} {{.State.ExitCode}}' "$id")
    read -r restarts oom exitcode <<<"$state"
    if [[ $restarts != 0 || $oom == true || $exitcode != 0 ]]; then
      docker inspect -f 'Restarted/OOM         : {{.Name}} restarts={{.RestartCount}} oom={{.State.OOMKilled}} exit={{.State.ExitCode}}' "$id"
      restart_issues=1
    fi
  done
  [[ $restart_issues == 0 ]] && echo 'Restarted/OOM         : NONE'

  if [[ $ok == 1 && $restart_issues == 0 ]]; then
    echo 'Overall              : HEALTHY'
  else
    echo 'Overall              : DEGRADED'
  fi
