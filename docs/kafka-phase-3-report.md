# Kafka Migration Phase 3 Report

Date: 2026-07-10

## Delivered

- Recorded exact synchronous request/delivery flow, schemas, RabbitMQ topology, idempotency, retry, observability, tests, and baseline performance.
- Added local single-node KRaft Kafka, Kafka UI, persistent data, health check, one-shot topic initialization, and configurable partition counts.
- Added `shared-event-contracts` with versioned JSON contracts and compatibility tests.
- Added feature-flagged Kafka-first intake. The producer uses `acks=all`, idempotence, bounded 32 MiB memory, finite blocking/request/delivery timeouts, retries, and LZ4 compression.
- Changed Kafka-mode `POST /notifications` to do only validation, admission control, durable Kafka publication, Redis acceptance caching, and `202` response.
- Added Redis-backed global/per-tenant rate limits, process concurrency cap, 256 KiB request cap, prompt 429/503/413 outcomes, and `Retry-After` on retryable rejection.
- Added eventual status lookup: PostgreSQL projection first, Redis acceptance cache second, otherwise 404.
- Added low-cardinality intake, rate, concurrency, Kafka latency/failure/buffer, payload, cache, and tenant-bucket metrics.
- Kept the synchronous PostgreSQL/outbox/RabbitMQ path behind `NOTIFICATION_BROKER_INTAKE=legacy`.
- Added local/Kubernetes configuration, runbooks, migration/rollback, delivery semantics, storage, retry/DLQ, failure, and load-test documentation.

## New module and infrastructure

- New module: `services/shared-event-contracts`.
- New runtime services: local `kafka`, `kafka-topic-init`, and `kafka-ui` containers.
- No orchestrator or notification projection service is introduced yet; those are the next controlled phases.

Topics created locally:

- Core: `notification.requests.v1`, five channel topics, `notification.delivery-results.v1`, `notification.status-events.v1`, `template.events.v1`, `preference.events.v1`.
- Retry/DLQ: 1m, 5m, 30m, and DLQ topics for email, SMS, push, webhook, and in-app.

## Verification

Commands completed successfully:

```bash
mvn test
mvn -f services/pom.xml test
mvn -f services/pom.xml package -DskipTests
docker compose up -d --build
docker compose ps
```

Final suite count is 22 tests: the 12-test baseline plus 3 event compatibility tests and 7 API intake tests. Added API tests cover durable publication, publish timeout, idempotent cache hit, partition key, schema validation, Redis rate limiting, and concurrency shedding.

Local stack verification confirmed:

- Kafka and PostgreSQL/Redis/RabbitMQ health checks passed.
- Topic initializer exited 0 after creating all 30 topics.
- HTTP response returned `202` with notification ID, request ID, `ACCEPTED`, and accepted timestamp.
- Kafka contained the matching JSON record under key `smoke-tenant:smoke-user-2` with ISO-8601 timestamp and schema version 1.
- Status lookup returned `ACCEPTED` from Redis before a permanent projection existed.
- Stopping Kafka caused a bounded `503 Service Unavailable`; the broker was restarted healthy.
- A 1,000 RPS offered burst produced prompt controlled 429s with no unexpected statuses.

## Benchmarks

These are local functional measurements, not production capacity claims.

| Scenario | Offered | Requests | Accepted | 429 | Unexpected | p50 | p95 | p99 | Max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Synchronous baseline | 20 RPS / 15s | 300 | 300 | 0 | 0 | 9.51 ms | 15.78 ms | not captured | 137.54 ms |
| Kafka-first steady | 100 RPS / 10s | 1,001 | 1,001 | 0 | 0 | 2.54 ms | 4.21 ms | 6.60 ms | 44.64 ms |
| Kafka-first overload | 1,000 RPS / 3s | 3,001 | 1,917 | 1,084 | 0 | 1.29 ms | 5.69 ms | 17.89 ms | 29.67 ms |

The steady summary is written to `build/load-results/intake-summary.json` and overload to `build/load-results/overload-summary.json` (ignored by Git but reproducible through the documented scripts).

## Current architecture and limitations

The API now durably buffers new commands in Kafka. Delivery is still the legacy RabbitMQ worker topology. Because Phase 4 is intentionally not included, Kafka-mode requests remain in `notification.requests.v1` and are not rendered or delivered yet. Use legacy intake when end-to-end delivery is required during this boundary.

Other gaps:

- No orchestrator, template/preference change events/outboxes, or local projections yet.
- Redis duplicate suppression is short-lived; durable tenant/idempotency deduplication belongs to the orchestrator.
- No Kafka channel consumers, retry scheduling, DLQ admin replay, results/status producers, or permanent notification projection.
- Full Testcontainers integration/resilience/rebalance/projection-outage tests are deferred until producers and consumers exist end to end.
- Kafka trace propagation, lag dashboards/alerts, KEDA worker scaling, and provider backpressure are later-phase work.
- Existing UUID and single-recipient/single-channel HTTP shapes are retained for compatibility. Event IDs are opaque strings, enabling later ULID adoption.
- The local broker has replication factor 1 and is unsuitable for production.
- Kubernetes YAML parses successfully, but server-schema dry-run was not possible because the configured local Kubernetes API was not running.

## Guarantees, outbox, backpressure, and storage

- Intake is at-least-once. A client may retry after an ambiguous 503; the idempotency key is mandatory and durable deduplication will occur in Phase 4.
- External providers remain at-least-once even if later Kafka processing uses transactions.
- The notification intake outbox is inactive in Kafka mode but remains intact for rollback.
- Transactional outboxes remain required for template/preference database changes plus Kafka publication.
- Backpressure is explicit: 413 for size, 429 for rate/concurrency, and 503 for admission/Kafka availability. There is no application queue in front of Kafka.
- PostgreSQL remains the planned permanent query projection. No database switch is justified by the current measurements.

## Rollback

Set `NOTIFICATION_BROKER_INTAKE=legacy` and restart the API. Verify template/preference services, PostgreSQL, outbox publisher, RabbitMQ, and workers. Do not replay already accepted Kafka requests into the legacy path without durable cross-path deduplication.

## Exact next phase

Phase 4: add `notification-orchestrator-service` to consume `notification.requests.v1`, persist processed event IDs and tenant/idempotency mappings transactionally, emit processing/rejection status events, resolve template/preferences behind an explicit fallback flag, render/rout deliveries, and publish compatible RabbitMQ jobs so Kafka intake regains end-to-end delivery before any worker is migrated.
