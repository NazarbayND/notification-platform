#!/usr/bin/env bash
set -euo pipefail

mvn -f services/pom.xml test
docker compose ps
curl --fail --silent --show-error http://localhost:8081/actuator/health/readiness
