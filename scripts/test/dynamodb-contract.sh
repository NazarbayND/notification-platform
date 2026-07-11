#!/usr/bin/env bash
set -euo pipefail

docker compose --profile dynamodb up -d --wait localstack
docker compose --profile dynamodb exec -T localstack /etc/localstack/init/ready.d/create-tables.sh
RUN_DYNAMODB_CONTRACT_TESTS=1 DYNAMODB_ENDPOINT="${DYNAMODB_ENDPOINT:-http://localhost:4566}" \
  mvn -f services/pom.xml -pl notification-projection-service -am \
  -Dtest=DynamoDbNotificationProjectionRepositoryContractTest -Dsurefire.failIfNoSpecifiedTests=false test
