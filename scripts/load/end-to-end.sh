#!/usr/bin/env bash
set -euo pipefail

echo "End-to-end Kafka delivery requires the Phase 4 orchestrator and Phase 6 worker migration."
echo "Running the currently available intake-to-RabbitMQ compatibility load scenario instead."
BASE_URL="${BASE_URL:-http://localhost:8081}" RATE="${RATE:-50}" DURATION="${DURATION:-30s}" \
  ./scripts/load/intake.sh
