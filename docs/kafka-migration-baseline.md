# Kafka Migration Baseline

Captured on 2026-07-10 before the Kafka migration code was introduced.

## Repository baseline

The workspace uses Java 21 source compatibility and Spring Boot 3.3.5. The local machine used Maven 3.9.16 and Docker 29.5.2 on Apple silicon. The Maven process reported a Java 26 runtime even though the project compiles with `--release 21`.

The existing intake path is implemented in `notification-api-service`:

1. Validate the single-recipient, single-channel HTTP request.
2. Call `preference-service` synchronously.
3. Call `template-service` synchronously to render content.
4. In one PostgreSQL transaction insert `notifications`, `notification_deliveries`, and `outbox_events`.
5. Return `202 Accepted` after the database commit.
6. `outbox-publisher-service` polls with `FOR UPDATE SKIP LOCKED`, publishes to RabbitMQ exchange `notification.delivery`, and updates retry state.
7. Workers consume durable queues `delivery.email`, `delivery.sms`, `delivery.push`, `delivery.in-app`, and `delivery.webhook`.

The API response called the state `ACCEPTED`, but before this migration it meant “committed to PostgreSQL/outbox,” not “accepted by Kafka.”

## Ownership and schemas

- `notification-api-service`: `notification_api.notifications`, `notification_api.notification_deliveries`, `notification_api.outbox_events`.
- `template-service`: `template.notification_templates`, `template.notification_products`.
- `preference-service`: `preference.user_notification_preferences`.
- Each worker owns a channel schema with `processed_events` and `delivery_attempts`; in-app additionally owns its queryable inbox.
- `outbox-publisher-service` is the one intentional exception to strict schema isolation: it updates the notification API's outbox publication lifecycle.

The notification idempotency constraint is `(product_id, idempotency_key)`. Workers deduplicate by a unique `event_id`. Worker provider calls are still at-least-once because a crash can occur between the external side effect and the local processed-event commit.

## Existing retry and failure behavior

- Outbox publication uses database retry metadata (`attempt_count`, `max_attempts`, `next_attempt_at`, `last_error`) and eventually `DEAD_LETTER`.
- RabbitMQ has one direct exchange and one durable queue per channel; there are no explicit RabbitMQ retry queues in code.
- Workers reject/requeue failed listener invocations according to container defaults. There is no non-blocking 1m/5m/30m retry-topic implementation.
- The outbox service exposes list, poll, and manual retry endpoints.

## Existing observability and load tooling

Micrometer/Prometheus and OpenTelemetry libraries are present. The synchronous path measures request, preference-check, and template-render duration. Workers expose consumed/processed/failed/duplicate counters and provider latency. The repository had two k6 scripts, but no machine-readable wrapper, burst scenario, broker-outage test, or API-specific unit tests.

## Untouched test baseline

Command:

```bash
mvn test
```

Result: **PASS**, 12 tests, 0 failures, 0 errors. Only `platform-common` and four worker/provider modules had tests. The API, template, preference, outbox publisher, email worker, and admin BFF had no tests.

## Untouched load baseline

The local Compose stack was started, and one active `default/welcome/EMAIL` template was inserted. The test used the synchronous DB/outbox path.

```bash
BASE_URL=http://localhost:8081 RATE=20 DURATION=15s \
  k6 run --summary-export=/tmp/notification-baseline.json tests/load/load-test.js
```

Results on this machine:

| Metric | Result |
| --- | ---: |
| Requests | 300 |
| Offered/accepted rate | 20.0 requests/s |
| HTTP failures | 0 |
| Average latency | 10.87 ms |
| p90 latency | 13.29 ms |
| p95 latency | 15.78 ms |
| Maximum latency | 137.54 ms |

This is a low-rate functional baseline, not a maximum-throughput claim. It includes synchronous preference/template calls and three database inserts per accepted request. Hardware, payload, provider behavior, duration, and offered rate are stated so the result is not mistaken for a production capacity claim.

## Baseline bottlenecks and risks

- Intake availability and latency are coupled to two HTTP dependencies and PostgreSQL.
- Every accepted request creates at least three synchronous rows and several indexes.
- PostgreSQL outbox polling is an extra durable buffering stage before RabbitMQ.
- There is no explicit global/per-tenant admission control, concurrency cap, request-body cap, or bounded broker-publish deadline.
- Retry timing and poison-message handling are not explicit at the worker boundary.
- The API had no tests for validation, idempotency, broker outage, or overload behavior.
- The public request currently models one recipient and one channel. Multi-recipient fan-out must be introduced compatibly in the orchestrator phase.
