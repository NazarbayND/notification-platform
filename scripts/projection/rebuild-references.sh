#!/usr/bin/env bash
set -euo pipefail

ORCHESTRATOR_URL="${ORCHESTRATOR_URL:-http://localhost:8091}"
BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
KAFKA_CONSUMER_GROUPS_BIN="${KAFKA_CONSUMER_GROUPS_BIN:-kafka-consumer-groups.sh}"

if [[ "${CONFIRM_REFERENCE_REBUILD:-}" != "yes" ]]; then
  echo "Refusing to clear reference projections without CONFIRM_REFERENCE_REBUILD=yes" >&2
  echo "Stop notification-orchestrator-service consumers before running the rebuild." >&2
  exit 2
fi

curl --fail-with-body --silent --show-error \
  --request POST "${ORCHESTRATOR_URL}/internal/projections/references/clear"

"${KAFKA_CONSUMER_GROUPS_BIN}" --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --group orchestrator-template-projection-v1 --topic template.events.v1 --reset-offsets --to-earliest --execute
"${KAFKA_CONSUMER_GROUPS_BIN}" --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --group orchestrator-preference-projection-v1 --topic preference.events.v1 --reset-offsets --to-earliest --execute

echo "Reference projections cleared and offsets reset. Start notification-orchestrator-service to replay changes."
