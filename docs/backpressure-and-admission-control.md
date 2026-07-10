# Backpressure and Admission Control

The intake API enforces, in order, a maximum request body size, Redis-backed global and per-tenant fixed-window limits, and a process-local concurrency semaphore. It then waits only for the bounded Kafka acknowledgement deadline.

Configuration:

```yaml
notification:
  intake:
    global-rate-per-second: 5000
    per-tenant-rate-per-second: 500
    max-concurrent-requests: 1000
    max-request-bytes: 262144
    max-recipients-per-request: 1000
    max-channels-per-request: 5
    kafka-publish-timeout: 3s
    acceptance-ttl: 20m
```

All values have environment-variable equivalents prefixed with `NOTIFICATION_INTAKE_`.

- Rate or concurrency rejection returns `429` and `Retry-After: 1`.
- Redis admission failure returns `503`; the service does not silently bypass configured protection.
- Kafka timeout, buffer exhaustion, or broker failure returns `503` and `Retry-After: 1`.
- Oversized requests return `413`.
- The producer uses a 32 MiB bounded buffer and finite `max.block.ms`, delivery timeout, and API wait deadline. There is no application queue in front of Kafka.

The current HTTP contract contains one recipient and one channel, so recipient/channel fan-out limits are explicit but trivially satisfied. Phase 4 will enforce them against the new batch shape before child events are generated.

Metrics use bounded labels. Per-tenant accepted traffic is exported in 32 stable hash buckets (`tenant_bucket=00..31`) rather than raw tenant IDs, preventing unbounded Prometheus series. Exact per-tenant rate state remains in Redis.

Kafka makes durable buffering possible; it does not make API capacity unlimited. Kafka lag governs internal processing demand, while provider quotas cap useful delivery concurrency.

## Verified results

Validated locally on 2026-07-10:

| Scenario | Offered load | Accepted | Controlled rejection | Unexpected status | p95 | p99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Steady Kafka intake | 1,001 requests at about 100 RPS | 1,001 | 0 | 0 | 4.21 ms | 6.60 ms |
| Per-tenant overload | 3,001 requests at about 1,000 RPS | 1,917 | 1,084 HTTP 429 | 0 | 5.69 ms | 17.89 ms |

Stopping the local Kafka broker produced a bounded HTTP `503 Service Unavailable` with the detail `Kafka did not durably accept the notification request`; Kafka was then restarted and returned to healthy state. These are local behavioral checks, not production capacity claims.
