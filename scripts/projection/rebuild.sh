#!/usr/bin/env bash
set -euo pipefail

PROJECTION_URL="${PROJECTION_URL:-http://localhost:8092}"
BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
KAFKA_CONSUMER_GROUPS_BIN="${KAFKA_CONSUMER_GROUPS_BIN:-kafka-consumer-groups.sh}"

if [[ "${CONFIRM_PROJECTION_REBUILD:-}" != "yes" ]]; then
  echo "Refusing to clear the projection without CONFIRM_PROJECTION_REBUILD=yes" >&2
  echo "Stop notification-projection-service consumers before running the rebuild." >&2
  exit 2
fi

curl --fail-with-body --silent --show-error \
  --request POST "${PROJECTION_URL}/projections/notifications/rebuild/clear"

reset_group() {
  local group="$1"
  local topic="$2"
  "${KAFKA_CONSUMER_GROUPS_BIN}" --bootstrap-server "${BOOTSTRAP_SERVERS}" \
    --group "${group}" --topic "${topic}" --reset-offsets --to-earliest --execute
}

reset_group notification-projection-requests-v1 notification.requests.v1
reset_group notification-projection-status-v1 notification.status-events.v1
reset_group notification-projection-results-v1 notification.delivery-results.v1

echo "Projection cleared and consumer offsets reset. Start notification-projection-service to replay Kafka history."
