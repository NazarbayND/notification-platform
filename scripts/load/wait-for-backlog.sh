#!/usr/bin/env bash
set -euo pipefail

GROUP="${GROUP:-notification-orchestrator-v1}"
BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-kafka:29092}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-300}"
POLL_SECONDS="${POLL_SECONDS:-5}"
OUTPUT="${OUTPUT:-build/load-results/backlog-drain.json}"
started_at="$(date +%s)"
mkdir -p "$(dirname "${OUTPUT}")"

lag() {
  docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server "${BOOTSTRAP_SERVERS}" --group "${GROUP}" --describe 2>/dev/null \
    | awk 'NR>2 && $6 ~ /^[0-9]+$/ {sum+=$6} END {print sum+0}'
}

while true; do
  current_lag="$(lag)"
  elapsed="$(( $(date +%s) - started_at ))"
  if [[ "${current_lag}" == "0" ]]; then
    jq -n --arg group "${GROUP}" --argjson drainSeconds "${elapsed}" \
      '{group:$group,finalLag:0,drainSeconds:$drainSeconds,status:"DRAINED"}' > "${OUTPUT}"
    break
  fi
  if (( elapsed >= TIMEOUT_SECONDS )); then
    jq -n --arg group "${GROUP}" --argjson finalLag "${current_lag}" --argjson drainSeconds "${elapsed}" \
      '{group:$group,finalLag:$finalLag,drainSeconds:$drainSeconds,status:"TIMEOUT"}' > "${OUTPUT}"
    exit 1
  fi
  sleep "${POLL_SECONDS}"
done

echo "Wrote ${OUTPUT}"
