#!/usr/bin/env bash
set -euo pipefail

SERVICES=(
  "common"
  "device-service"
  "command-service"
  "event-service"
  "sim-service"
)

for service in "${SERVICES[@]}"; do
  echo "--- Running tests for ${service} ---"
  ./gradlew ":${service}:test"
  echo "--- ${service} tests passed ---"
  echo
done

echo "All individual service test runs completed successfully."
