# Notification Platform Load Testing

This directory contains k6 stress tests for validating API throughput, queue behavior, worker recovery, and provider failure handling. The tests are intentionally outside the backend code path and do not change business logic.

## Prerequisites

- k6 installed locally.
- Local stack running with `docker compose up -d`.
- Backend running on `http://localhost:8080`.
- RabbitMQ management UI available on `http://localhost:15672`.
- Prometheus available on `http://localhost:9090`.
- Grafana available on `http://localhost:3001`.

Most scripts create a unique product and active EMAIL template in `setup()`. To reuse existing data, pass:

```bash
PRODUCT_ID=<uuid> TEMPLATE_KEY=<template-key>
```

Common variables:

```bash
BASE_URL=http://localhost:8080
RABBITMQ_API=http://localhost:15672
RABBITMQ_USER=notification
RABBITMQ_PASSWORD=notification
RABBITMQ_VHOST=/
```

## Smoke Test

Purpose: verify that notification creation works before running larger tests.

Scenario:

- 1 virtual user.
- 10 notification creation requests.
- Thresholds validate low error rate and p95 latency below 500 ms.

Run:

```bash
k6 run tests/load/smoke.js
```

## Notification Throughput

Purpose: measure steady notification creation throughput.

Scenario:

- 100 virtual users.
- 5 minutes.
- Sends `POST /api/v1/notifications`.
- Captures latency, error rate, and created notification count.

Run:

```bash
k6 run tests/load/notifications.js
```

Overrides:

```bash
VUS=200 DURATION=10m k6 run tests/load/notifications.js
```

Primary signals:

- API latency: `http_req_duration`.
- API error rate: `http_req_failed`.
- Backend throughput: `rate(notifications_created_total[1m])`.
- RabbitMQ publish rate: `rate(rabbitmq_messages_published_total[1m])`.

## Batch Notification Test

Purpose: validate large batch creation and outbox growth.

Scenario:

- Posts `POST /api/v1/notification-batches`.
- Default sizes: 100, 1000, 10000 notifications.
- Captures batch creation time, accepted item count, outbox pending count, and retry scheduled count.

Run:

```bash
k6 run tests/load/batches.js
```

For a shorter local check:

```bash
BATCH_SIZES=100,1000 k6 run tests/load/batches.js
```

Primary signals:

- Batch creation latency: `k6_batch_creation_time`.
- Outbox backlog: `outbox_pending_count`.
- Queue backlog: RabbitMQ `messages ready` by queue.
- Processing completion: outbox pending returns toward zero and delivery sent rate catches up.

## Spike Test

Purpose: measure behavior under a sudden traffic surge.

Scenario:

- Starts at 10 users.
- Ramps quickly to 1000 users.
- Holds, then ramps down.
- Samples RabbitMQ backlog and outbox pending count.

Run:

```bash
k6 run tests/load/spike.js
```

Safer laptop run:

```bash
SPIKE_VUS=250 SPIKE_HOLD_DURATION=1m k6 run tests/load/spike.js
```

Expected behavior:

- API latency rises but remains bounded.
- RabbitMQ queue depth may grow during the spike.
- Queue depth should drain after ramp down.
- `outbox_pending_count` should not grow without bound.

## Soak Test

Purpose: look for memory leaks, database pressure, and queue buildup.

Scenario:

- 100 virtual users.
- 30 minutes.
- Sends steady notification traffic.
- Samples outbox pending, retry scheduled, and dead-lettered delivery gauges.

Run:

```bash
k6 run tests/load/soak.js
```

Short local run:

```bash
DURATION=5m VUS=50 k6 run tests/load/soak.js
```

Watch:

- JVM memory and GC in Grafana.
- Hikari connection usage if enabled by Micrometer.
- RabbitMQ queue depth.
- `deliveries_dead_lettered_count`.
- `outbox_pending_count`.

## Provider Failure Simulation

Purpose: validate retry scheduling, temporary failure handling, and dead-letter behavior.

The backend supports provider failure modes through `NOTIFICATION_EMAIL_FAILURE_MODE`:

- `SUCCESS`
- `TEMPORARY_FAILURE_503`
- `PERMANENT_FAILURE`
- `TIMEOUT`

For a 50 percent success / 50 percent temporary failure test without changing business logic, run two backend instances against the same local infrastructure:

- Success app: `NOTIFICATION_EMAIL_FAILURE_MODE=SUCCESS`, for example on port 8080.
- Failure app: `NOTIFICATION_EMAIL_FAILURE_MODE=TEMPORARY_FAILURE_503`, for example on port 8081.

Then run:

```bash
BASE_URL=http://localhost:8080 \
FAILURE_BASE_URL=http://localhost:8081 \
k6 run tests/load/provider-failure.js
```

Single-app temporary failure run:

```bash
NOTIFICATION_EMAIL_FAILURE_MODE=TEMPORARY_FAILURE_503 SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
k6 run tests/load/provider-failure.js
```

Expected behavior:

- API notification creation should still succeed.
- Temporary failures create `delivery_attempts` rows.
- Failed sends move to `RETRY_SCHEDULED`.
- Retry queue depth grows while failures continue.
- After max attempts, deliveries move to `DEAD_LETTERED`.

## Worker Recovery Test

Purpose: validate queue backlog growth while the EMAIL worker is offline and backlog drain after worker recovery.

Because the current app is a modular monolith, the EMAIL worker can be disabled by starting the app with Rabbit listeners disabled while keeping the API and outbox publisher online:

```bash
SPRING_PROFILES_ACTIVE=local \
SPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP=false \
mvn spring-boot:run
```

Run load:

```bash
k6 run tests/load/worker-recovery.js
```

Observe RabbitMQ backlog growing in:

- `notifications.high.email`
- `notifications.normal.email`
- `notifications.low.email`

Then restart the backend normally:

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

Expected behavior:

- RabbitMQ backlog grows while listeners are disabled.
- Outbox pending should remain bounded because publishing still works.
- After restart, RabbitMQ backlog drains.
- `deliveries_sent_total` increases as workers catch up.
- Duplicate messages remain idempotent because workers skip terminal delivery states.

## PromQL Examples

Notifications per second:

```promql
rate(notifications_created_total[1m])
```

Notification p95 latency:

```promql
histogram_quantile(0.95, sum(rate(notification_create_duration_seconds_bucket[5m])) by (le))
```

Delivery failures:

```promql
rate(deliveries_failed_total[1m])
```

Outbox backlog:

```promql
outbox_pending_count
```

RabbitMQ backlog:

```promql
sum(rabbitmq_queue_messages_ready{queue=~"notifications\\..*\\.email"}) by (queue)
```

Retry rate:

```promql
rate(deliveries_failed_total[1m])
```

Worker throughput:

```promql
rate(rabbitmq_messages_consumed_total[1m])
```

Delivery success rate:

```promql
rate(deliveries_sent_total[1m])
/
clamp_min(rate(delivery_attempts_total[1m]), 1)
```

Redis cache hit ratio:

```promql
rate(redis_cache_hit_total[5m])
/
clamp_min(rate(redis_cache_hit_total[5m]) + rate(redis_cache_miss_total[5m]), 1)
```

## Observability During Tests

Grafana:

- Main dashboard: `Notification Platform`.
- Stress dashboard: `Notification Platform Stress`.
- URL: `http://localhost:3001`.

Prometheus:

- Query raw metrics at `http://localhost:9090`.
- Backend scrape target should be healthy.
- RabbitMQ scrape target should be healthy.

RabbitMQ UI:

- URL: `http://localhost:15672`.
- Login: `notification / notification`.
- Watch ready/unacked messages by priority queue.

Jaeger:

- URL: `http://localhost:16686`.
- Search for service `notification-platform`.
- Use traces to inspect slow notification creation, outbox publishing, RabbitMQ publishing, worker processing, and provider sending.

## Interpreting Results

Healthy load behavior:

- API error rate stays near zero during smoke and throughput tests.
- Latency increases gradually, not sharply.
- Outbox pending count returns toward zero after load ends.
- RabbitMQ queue depth may rise during bursts but drains after traffic stops.
- Delivery sent rate follows notification creation rate after normal queue delay.
- Dead-letter count stays flat unless testing permanent or repeated provider failure.

Warning signs:

- `outbox_pending_count` grows continuously while RabbitMQ is healthy.
- RabbitMQ ready messages grow after load ends.
- `delivery_processing_duration` increases steadily during a soak test.
- `deliveries_dead_lettered_total` increases during success-mode tests.
- Redis hit ratio is unexpectedly low after warmup for template and preference reads.

