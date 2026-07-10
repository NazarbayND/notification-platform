#!/usr/bin/env bash
set -euo pipefail

if [[ "${RUN_DESTRUCTIVE_FAILURE_TESTS:-0}" != "1" ]]; then
  echo "Failure scenarios stop local infrastructure. Re-run with RUN_DESTRUCTIVE_FAILURE_TESTS=1 in an isolated stack."
  exit 0
fi

docker compose stop kafka
trap 'docker compose start kafka' EXIT
code="$(curl --silent --output /tmp/kafka-outage-response.json --write-out '%{http_code}' \
  -X POST http://localhost:8081/notifications -H 'Content-Type: application/json' \
  -d '{"tenantId":"failure-test","productId":"default","userId":"user-1","channel":"EMAIL","templateKey":"welcome","idempotencyKey":"kafka-outage-test","destination":"user@example.com"}')"
[[ "${code}" == "503" ]]
