#!/usr/bin/env bash
set -euo pipefail

mkdir -p build/load-results
SUMMARY_OUTPUT="${SUMMARY_OUTPUT:-build/load-results/end-to-end-summary.json}"
mkdir -p "$(dirname "${SUMMARY_OUTPUT}")"
BASE_URL="${BASE_URL:-http://localhost:8081}" \
PROJECTION_URL="${PROJECTION_URL:-http://localhost:8092}" \
RATE="${RATE:-50}" \
DURATION="${DURATION:-30s}" \
k6 run --summary-trend-stats="avg,min,med,p(90),p(95),p(99),max" \
  --summary-export="${SUMMARY_OUTPUT}" tests/load/end-to-end-test.js
