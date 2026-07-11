# Notification Platform Load Tests

Kafka-first k6 scenarios for intake, end-to-end delivery, burst, and stress behavior.

For the Kafka-first intake test and a machine-readable summary, prefer:

```sh
RATE=100 DURATION=30s ./scripts/load/intake.sh
```

## Scenarios

```sh
RATE=100 DURATION=5m ./scripts/load/intake.sh
RATE=50 DURATION=5m ./scripts/load/end-to-end.sh
BURST_RATE=1000 ./scripts/load/burst.sh
MAX_VUS=2000 ./scripts/load/stress.sh
```

The end-to-end scenario polls `notification-projection-service` until delivery reaches a terminal state and records `e2e_delivery_duration`. All wrappers export machine-readable summaries under `build/load-results/`.

## Backlog and platform summaries

```sh
GROUP=notification-orchestrator-v1 ./scripts/load/wait-for-backlog.sh
./scripts/load/collect-platform-summary.sh
```

## Configuration

Use `thresholds.env.example` as the explicit acceptance-threshold template. Important variables include `BASE_URL`, `PROJECTION_URL`, `RATE`, `DURATION`, `MAX_VUS`, `CHANNEL`, `TEMPLATE_KEY`, `E2E_P95_MS`, `MAX_STATUS_TIMEOUTS`, and `STATUS_TIMEOUT_SECONDS`.

## Kubernetes

```sh
BASE_URL=http://localhost:8081 PROJECTION_URL=http://localhost:8092 ./scripts/load/end-to-end.sh
```

## How To Interpret Results

- If API latency grows, inspect admission control and Kafka producer latency.
- If API latency is stable but request lag grows, inspect the orchestrator and its outbox.
- If channel lag grows, inspect provider quotas, worker concurrency, and retries.
- If result lag grows, inspect the active projection store and connection/capacity limits.
- If provider errors grow, the provider or test provider is the bottleneck.

No Phase 4–10 scenario has been run yet. Do not infer capacity from the configured rates or thresholds.
