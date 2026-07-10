#!/usr/bin/env bash
set -euo pipefail

BOOTSTRAP_SERVERS="${1:-${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}}"
KAFKA_TOPICS_BIN="${KAFKA_TOPICS_BIN:-}"

if [[ -z "${KAFKA_TOPICS_BIN}" ]]; then
  if command -v kafka-topics.sh >/dev/null 2>&1; then
    KAFKA_TOPICS_BIN="$(command -v kafka-topics.sh)"
  elif [[ -x /opt/kafka/bin/kafka-topics.sh ]]; then
    KAFKA_TOPICS_BIN=/opt/kafka/bin/kafka-topics.sh
  else
    echo "kafka-topics.sh was not found; run this script in the Kafka container or set KAFKA_TOPICS_BIN" >&2
    exit 1
  fi
fi

create_topic() {
  local topic="$1"
  local partitions="$2"
  "${KAFKA_TOPICS_BIN}" --bootstrap-server "${BOOTSTRAP_SERVERS}" \
    --create --if-not-exists --topic "${topic}" --partitions "${partitions}" --replication-factor 1
}

create_topic notification.requests.v1 "${NOTIFICATION_REQUEST_PARTITIONS:-6}"
create_topic notification.email.v1 "${CHANNEL_TOPIC_PARTITIONS:-6}"
create_topic notification.sms.v1 "${CHANNEL_TOPIC_PARTITIONS:-6}"
create_topic notification.push.v1 "${CHANNEL_TOPIC_PARTITIONS:-6}"
create_topic notification.webhook.v1 "${CHANNEL_TOPIC_PARTITIONS:-6}"
create_topic notification.in-app.v1 "${CHANNEL_TOPIC_PARTITIONS:-6}"
create_topic notification.delivery-results.v1 "${DELIVERY_RESULT_PARTITIONS:-6}"
create_topic notification.status-events.v1 "${STATUS_EVENT_PARTITIONS:-6}"
create_topic template.events.v1 "${REFERENCE_EVENT_PARTITIONS:-3}"
create_topic preference.events.v1 "${REFERENCE_EVENT_PARTITIONS:-3}"

for channel in email sms push webhook in-app; do
  create_topic "notification.${channel}.retry-1m.v1" "${RETRY_TOPIC_PARTITIONS:-6}"
  create_topic "notification.${channel}.retry-5m.v1" "${RETRY_TOPIC_PARTITIONS:-6}"
  create_topic "notification.${channel}.retry-30m.v1" "${RETRY_TOPIC_PARTITIONS:-6}"
  create_topic "notification.${channel}.dlq.v1" "${DLQ_TOPIC_PARTITIONS:-3}"
done

"${KAFKA_TOPICS_BIN}" --bootstrap-server "${BOOTSTRAP_SERVERS}" --list
