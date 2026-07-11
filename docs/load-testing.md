# Load Testing

Run the Kafka intake scenario:

```bash
RATE=100 DURATION=30s ./scripts/load/intake.sh
```

The wrapper writes a machine-readable k6 summary to `build/load-results/intake-summary.json`. Always report offered RPS, accepted/rejected RPS, status mix, p50/p95/p99/max latency, payload size, duration, hardware, Kafka partitions, and service replicas.

The Phase 1 synchronous baseline was 300/300 accepted at 20 RPS for 15 seconds, p95 15.78 ms on the documented local machine. It is not a maximum capacity result.

## Recorded Phase 3 results

Run on 2026-07-10 against the local single-broker KRaft stack:

| Scenario | Duration | Requests | Accepted | HTTP 429 | Unexpected | p50 | p95 | p99 | Max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Kafka-first steady | 10s at 100 RPS | 1,001 | 1,001 | 0 | 0 | 2.54 ms | 4.21 ms | 6.60 ms | 44.64 ms |
| Kafka-first overload | 3s at 1,000 RPS | 3,001 | 1,917 | 1,084 | 0 | 1.29 ms | 5.69 ms | 17.89 ms | 29.67 ms |

The overload run intentionally treats `429` as a controlled outcome. k6's built-in `http_req_failed` metric still counts non-2xx responses, while the scenario-specific check confirmed every response was one of `202`, `429`, or `503` and `intake_unexpected_status` remained zero.

Burst and end-to-end wrappers are present. Full orchestrator/worker/projection throughput measurements have not been run for the Phase 4–8 implementation and are intentionally deferred. Overload success means prompt explicit 429/503 responses, bounded memory/concurrency, retained Kafka data, and backlog recovery—not zero rejection.
