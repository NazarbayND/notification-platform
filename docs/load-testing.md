# Load Testing

Run the Kafka intake scenario:

```bash
RATE=100 DURATION=30s ./scripts/load/intake.sh
```

Available final-architecture scenarios:

```bash
RATE=100 DURATION=5m ./scripts/load/intake.sh
CHANNEL=PUSH RATE=50 DURATION=5m PRE_ALLOCATED_VUS=250 ./scripts/load/end-to-end.sh
BURST_RATE=1000 ./scripts/load/burst.sh
MAX_VUS=2000 ./scripts/load/stress.sh
GROUP=notification-orchestrator-v1 ./scripts/load/wait-for-backlog.sh
./scripts/load/collect-platform-summary.sh
```

Copy values from `tests/load/thresholds.env.example` into the environment to make acceptance thresholds explicit. k6 summaries, backlog drain time, and Prometheus platform metrics are written under `build/load-results/`. Set `SUMMARY_OUTPUT` to retain multiple runs instead of replacing the scenario's default summary file.

End-to-end runs require an active template for the selected channel. The recorded PUSH run used active `default/welcome/PUSH` content and the deterministic push test provider. Do not compare an SMTP/MailHog run directly with an in-process test-provider run.

The wrapper writes a machine-readable k6 summary to `build/load-results/intake-summary.json`. Always report offered RPS, accepted/rejected RPS, status mix, p50/p95/p99/max latency, payload size, duration, hardware, Kafka partitions, and service replicas.

The Phase 1 synchronous baseline was 300/300 accepted at 20 RPS for 15 seconds, p95 15.78 ms on the documented local machine. It is not a maximum capacity result.

## Post-cleanup verification

The cleaned Kafka-only local topology was verified on 2026-07-24 with
PostgreSQL, Kafka, Redis, the eleven backend services, the admin frontend,
Prometheus, Grafana, and OpenTelemetry running in Docker Compose.

| Scenario | Duration / rate | Completed | Result | p50 | p95 | p99 | Max |
| --- | --- | ---: | --- | ---: | ---: | ---: | ---: |
| Kafka intake smoke load | 5 s / 20 RPS | 101 | 101 accepted; 0 failed or unexpected | 7.64 ms | 13.02 ms | 21.67 ms | 38.74 ms |
| PUSH end-to-end load | 5 s / 10 RPS | 51 | 51 delivered; 0 failed, timed out, or dropped | 272 ms | 533.5 ms | 537.5 ms | 539 ms |

The machine-readable summaries are
`build/load-results/cleanup-intake-summary.json` and
`build/load-results/cleanup-e2e-summary.json`. These are short local smoke
loads, not production capacity claims.

## Recorded Phase 3 results

Run on 2026-07-10 against the local single-broker KRaft stack:

| Scenario | Duration | Requests | Accepted | HTTP 429 | Unexpected | p50 | p95 | p99 | Max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Kafka-first steady | 10s at 100 RPS | 1,001 | 1,001 | 0 | 0 | 2.54 ms | 4.21 ms | 6.60 ms | 44.64 ms |
| Kafka-first overload | 3s at 1,000 RPS | 3,001 | 1,917 | 1,084 | 0 | 1.29 ms | 5.69 ms | 17.89 ms | 29.67 ms |

The overload run intentionally treats `429` as a controlled outcome. k6's built-in `http_req_failed` metric still counts non-2xx responses, while the scenario-specific check confirmed every response was one of `202`, `429`, or `503` and `intake_unexpected_status` remained zero.

## Recorded final-architecture results

Run on 2026-07-12 on the documented Apple-silicon local Docker environment, using one Kafka broker, six request/channel/result partitions, one replica per service, PostgreSQL 16, and deterministic provider behavior:

| Scenario | Duration | Offered/completed | Outcome | p50 | p95 | p99 | Max |
| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: |
| Kafka intake steady | 30s at 100 RPS | 3,001 | 3,001 accepted; 0 unexpected | 2.69 ms | 6.62 ms | 162.88 ms | 446.59 ms |
| PUSH end-to-end | 30s at 50 RPS | 1,501 | 1,501 delivered; 0 failed, timed out, or dropped | 2.76 s | 4.27 s | 4.53 s | 4.55 s |

The end-to-end latency includes Kafka intake, orchestration, rendering, channel delivery, result projection, and 250 ms status polling. The built-in HTTP failure percentage in the captured summary represents transient projection `404` responses during eventual-consistency polling; scenario counters and terminal status checks are authoritative. Subsequent test code marks those poll responses as expected.

The load run found and fixed two runtime defects:

- Twelve default Hikari pools exhausted PostgreSQL's 100-connection default. Local pools are now capped at five connections with one minimum idle connection; observed steady connection use after restart was 27/100.
- Repeated Kafka `nack` calls advanced the worker's provider-rate deadline and starved partitions. The limiter now uses a fixed one-second quota window, is regression-tested, and drained the previously stuck 333-message PUSH backlog in 7 seconds.

The new 1,000 RPS burst rerun was not executed because the local command-approval quota was exhausted. The earlier Phase 3 1,000 RPS result above remains the current admission-control measurement; it must not be presented as a post-fix Phase 4–10 benchmark. Overload success means prompt explicit 429/503 responses, bounded memory/concurrency, retained Kafka data, and backlog recovery—not zero rejection.

When results are collected, report offered/accepted/rejected RPS, status mix, p50/p95/p99/max API and end-to-end latency, Kafka/orchestrator/worker/projection rates, consumer lag, drain time, database write rate, CPU, memory, GC pause rate, connection-pool utilization, payload size, hardware, partitions, replicas, provider behavior, and duration.
