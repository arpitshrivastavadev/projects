#!/bin/bash
# Memory watchdog — kills and restarts Docker stack if free RAM drops below threshold.
# Run as a background service to prevent host display crashes from container memory exhaustion.
# Usage: ./mem-watchdog.sh [threshold_mb]  (default: 1500 MB free)

THRESHOLD_MB=${1:-1500}
LOG=/tmp/police112-mem-watchdog.log
COMPOSE_DIR="$(cd "$(dirname "$0")" && pwd)"

check_memory() {
  awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo
}

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" | tee -a "$LOG"
}

log "Watchdog started. Available RAM threshold: ${THRESHOLD_MB}MB. Compose dir: $COMPOSE_DIR"

while true; do
  AVAIL=$(check_memory)
  if [ "$AVAIL" -lt "$THRESHOLD_MB" ]; then
    log "WARNING: Only ${AVAIL}MB RAM available (threshold ${THRESHOLD_MB}MB). Stopping Docker stack to protect host."
    cd "$COMPOSE_DIR" && docker compose stop
    log "Docker stack stopped. Waiting 60s before restart attempt..."
    sleep 60
    AVAIL=$(check_memory)
    if [ "$AVAIL" -ge "$THRESHOLD_MB" ]; then
      log "Memory recovered to ${AVAIL}MB. Restarting stack..."
      cd "$COMPOSE_DIR" && docker compose start
      log "Stack restarted."
    else
      log "Memory still low (${AVAIL}MB). Not restarting. Manual intervention needed."
    fi
  fi
  sleep 30
done
