#!/usr/bin/env bash
set -euo pipefail

PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
OUTPUT="${OUTPUT:-build/load-results/platform-summary.json}"
mkdir -p "$(dirname "${OUTPUT}")"

query() {
  curl --fail --silent --show-error --get "${PROMETHEUS_URL}/api/v1/query" \
    --data-urlencode "query=$1" | jq -c '[.data.result[].value[1] | tonumber] | if length == 0 then null else add end'
}

accepted_rps="$(query 'sum(rate(notification_intake_accepted_total[1m]))')"
rejected_rps="$(query 'sum(rate(notification_intake_rejected_total[1m]))')"
orchestrator_rps="$(query 'sum(rate(orchestrator_generated_deliveries_total[1m]))')"
worker_rps="$(query 'sum(rate(worker_messages_processed_total[1m]))')"
projection_rps="$(query 'sum(rate(projection_update_latency_seconds_count[1m]))')"
worker_retries="$(query 'sum(increase(worker_retries_total[5m]))')"
worker_dlq="$(query 'sum(increase(worker_dlq_total[5m]))')"
cpu_cores="$(query 'sum(rate(process_cpu_seconds_total[1m]))')"
heap_bytes="$(query 'sum(jvm_memory_used_bytes{area="heap"})')"
gc_pause_rate="$(query 'sum(rate(jvm_gc_pause_seconds_sum[1m]))')"
hikari_active="$(query 'sum(hikaricp_connections_active)')"

jq -n \
  --arg generatedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --argjson acceptedRps "${accepted_rps}" \
  --argjson rejectedRps "${rejected_rps}" \
  --argjson orchestratorRps "${orchestrator_rps}" \
  --argjson workerRps "${worker_rps}" \
  --argjson projectionRps "${projection_rps}" \
  --argjson workerRetries "${worker_retries}" \
  --argjson workerDlq "${worker_dlq}" \
  --argjson cpuCores "${cpu_cores}" \
  --argjson heapBytes "${heap_bytes}" \
  --argjson gcPauseSecondsPerSecond "${gc_pause_rate}" \
  --argjson hikariActive "${hikari_active}" \
  '{generatedAt:$generatedAt,acceptedRps:$acceptedRps,rejectedRps:$rejectedRps,
    orchestratorDeliveriesPerSecond:$orchestratorRps,workerCompletionsPerSecond:$workerRps,
    projectionUpdatesPerSecond:$projectionRps,retriesLastFiveMinutes:$workerRetries,
    dlqLastFiveMinutes:$workerDlq,cpuCores:$cpuCores,heapBytes:$heapBytes,
    gcPauseSecondsPerSecond:$gcPauseSecondsPerSecond,hikariActiveConnections:$hikariActive}' > "${OUTPUT}"

echo "Wrote ${OUTPUT}"
