#!/usr/bin/env bash
set -euo pipefail

mkdir -p build/load-results
BASE_URL="${BASE_URL:-http://localhost:8081}" \
MAX_VUS="${MAX_VUS:-2000}" \
k6 run --summary-trend-stats="avg,min,med,p(90),p(95),p(99),max" \
  --summary-export=build/load-results/stress-summary.json tests/load/throughput-test.js
