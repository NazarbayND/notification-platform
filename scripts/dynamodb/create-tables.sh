#!/usr/bin/env bash
set -euo pipefail

ENDPOINT="${DYNAMODB_ENDPOINT:-http://localhost:4566}"
REGION="${AWS_REGION:-us-east-1}"
export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-local}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-local}"
NOTIFICATIONS_TABLE="${DYNAMODB_NOTIFICATIONS_TABLE:-notification-projections}"
DELIVERIES_TABLE="${DYNAMODB_DELIVERIES_TABLE:-notification-deliveries}"
ATTEMPTS_TABLE="${DYNAMODB_ATTEMPTS_TABLE:-notification-delivery-attempts}"
PROCESSED_TABLE="${DYNAMODB_PROCESSED_EVENTS_TABLE:-notification-processed-events}"

if command -v awslocal >/dev/null 2>&1; then
  AWS=(awslocal)
else
  AWS=(aws --endpoint-url "${ENDPOINT}" --region "${REGION}")
fi

exists() { "${AWS[@]}" dynamodb describe-table --table-name "$1" >/dev/null 2>&1; }

if ! exists "${NOTIFICATIONS_TABLE}"; then
  "${AWS[@]}" dynamodb create-table --table-name "${NOTIFICATIONS_TABLE}" --billing-mode PAY_PER_REQUEST \
    --attribute-definitions AttributeName=notificationId,AttributeType=S AttributeName=tenantId,AttributeType=S AttributeName=tenantUserKey,AttributeType=S AttributeName=requestedAtEpoch,AttributeType=N \
    --key-schema AttributeName=notificationId,KeyType=HASH \
    --global-secondary-indexes 'IndexName=tenant-requested-index,KeySchema=[{AttributeName=tenantId,KeyType=HASH},{AttributeName=requestedAtEpoch,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
      'IndexName=user-requested-index,KeySchema=[{AttributeName=tenantUserKey,KeyType=HASH},{AttributeName=requestedAtEpoch,KeyType=RANGE}],Projection={ProjectionType=ALL}'
fi

if ! exists "${DELIVERIES_TABLE}"; then
  "${AWS[@]}" dynamodb create-table --table-name "${DELIVERIES_TABLE}" --billing-mode PAY_PER_REQUEST \
    --attribute-definitions AttributeName=deliveryId,AttributeType=S AttributeName=notificationId,AttributeType=S AttributeName=updatedAtEpoch,AttributeType=N \
    --key-schema AttributeName=deliveryId,KeyType=HASH \
    --global-secondary-indexes 'IndexName=notification-updated-index,KeySchema=[{AttributeName=notificationId,KeyType=HASH},{AttributeName=updatedAtEpoch,KeyType=RANGE}],Projection={ProjectionType=ALL}'
fi

if ! exists "${ATTEMPTS_TABLE}"; then
  "${AWS[@]}" dynamodb create-table --table-name "${ATTEMPTS_TABLE}" --billing-mode PAY_PER_REQUEST \
    --attribute-definitions AttributeName=eventId,AttributeType=S --key-schema AttributeName=eventId,KeyType=HASH
fi

if ! exists "${PROCESSED_TABLE}"; then
  "${AWS[@]}" dynamodb create-table --table-name "${PROCESSED_TABLE}" --billing-mode PAY_PER_REQUEST \
    --attribute-definitions AttributeName=consumerEventId,AttributeType=S --key-schema AttributeName=consumerEventId,KeyType=HASH
fi

for table in "${NOTIFICATIONS_TABLE}" "${DELIVERIES_TABLE}" "${ATTEMPTS_TABLE}" "${PROCESSED_TABLE}"; do
  "${AWS[@]}" dynamodb wait table-exists --table-name "${table}"
  ttl_status="$("${AWS[@]}" dynamodb describe-time-to-live --table-name "${table}" \
    --query 'TimeToLiveDescription.TimeToLiveStatus' --output text)"
  if [[ "${ttl_status}" != "ENABLED" && "${ttl_status}" != "ENABLING" ]]; then
    "${AWS[@]}" dynamodb update-time-to-live --table-name "${table}" \
      --time-to-live-specification Enabled=true,AttributeName=expiresAt >/dev/null
  fi
done

echo "DynamoDB projection tables are ready."
