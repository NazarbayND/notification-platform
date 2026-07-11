#!/usr/bin/env bash
set -euo pipefail

if [[ "${RUN_DESTRUCTIVE_FAILURE_TESTS:-0}" != "1" ]]; then
  echo "Failure scenarios stop local infrastructure. Re-run with RUN_DESTRUCTIVE_FAILURE_TESTS=1 in an isolated stack."
  exit 0
fi

SCENARIO="${SCENARIO:-kafka-outage}"

submit() {
  local key="$1"
  curl --silent --output "/tmp/${key}-response.json" --write-out '%{http_code}' \
    -X POST http://localhost:8081/notifications -H 'Content-Type: application/json' \
    -d "{\"tenantId\":\"failure-test\",\"productId\":\"default\",\"userId\":\"user-1\",\"channel\":\"EMAIL\",\"templateKey\":\"welcome\",\"idempotencyKey\":\"${key}\",\"destination\":\"user@example.com\"}"
}

case "${SCENARIO}" in
  kafka-outage)
    docker compose stop kafka
    trap 'docker compose start kafka' EXIT
    [[ "$(submit kafka-outage-test)" == "503" ]]
    ;;
  postgres-outage)
    docker compose stop postgres
    trap 'docker compose start postgres' EXIT
    code="$(submit postgres-outage-test)"
    [[ "${code}" == "202" ]]
    ;;
  projection-restart)
    docker compose restart notification-projection-service
    ;;
  worker-restart)
    docker compose restart "${WORKER_SERVICE:-email-worker-service}"
    ;;
  provider-slowdown)
    trap 'docker compose up -d --force-recreate email-worker-service' EXIT
    EMAIL_PROVIDER=test TEST_PROVIDER_LATENCY_MS="${TEST_PROVIDER_LATENCY_MS:-2000}" \
      docker compose up -d --force-recreate email-worker-service
    CHANNEL=EMAIL RATE="${RATE:-50}" DURATION="${DURATION:-60s}" ./scripts/load/end-to-end.sh
    ;;
  *)
    echo "Unknown SCENARIO=${SCENARIO}" >&2
    exit 2
    ;;
esac
