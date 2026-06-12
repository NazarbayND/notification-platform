# Notification Platform Observability

This project now exposes the three production signals for every backend service:

- Metrics through Spring Boot Actuator at `/actuator/prometheus`
- JSON logs to stdout with `correlationId`, `traceId`, `spanId`, `eventId`, `notificationId`, and `channel` when available
- Distributed traces through OTLP HTTP to the OpenTelemetry Collector

Covered services:

- `notification-api-service`
- `template-service`
- `preference-service`
- `outbox-publisher-service`
- `email-worker-service`
- `sms-worker-service`
- `push-worker-service`
- `in-app-worker-service`
- `webhook-worker-service`
- `admin-bff-service`

## Local Run

Start the full platform and observability stack:

```bash
docker compose -f docker-compose.microservices.yml up --build
```

Useful URLs:

- Admin UI: http://localhost:5173
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000
- Jaeger: http://localhost:16686
- Loki: http://localhost:3100
- RabbitMQ management: http://localhost:15672

Grafana defaults:

- User: `admin`
- Password: `admin`

## Dashboards

Grafana provisions these dashboards from `observability/grafana/dashboards`:

- Notification Platform Overview
- Outbox Dashboard
- Worker Dashboard
- Admin BFF Dashboard

Primary panels cover:

- Notification request volume and latency
- Template render volume, validation failures, and latency
- Preference check volume and latency
- Outbox pending, processing, failed, dead letter, oldest pending age, batch size, publish latency, lock wait latency
- Worker consumed, processed, failed, duplicate skipped, processing latency
- Delivery attempts by channel/provider/status
- Provider request latency and errors
- Admin BFF downstream errors

## Metrics

Custom application metrics include:

- `notification_created_total`
- `notification_rejected_total`
- `notification_request_duration_seconds`
- `template_render_total`
- `template_validation_failed_total`
- `template_render_duration_seconds`
- `preference_check_total`
- `preference_check_duration_seconds`
- `outbox_pending_total`
- `outbox_processing_total`
- `outbox_failed_total`
- `outbox_dead_letter_total`
- `outbox_oldest_pending_age_seconds`
- `outbox_publish_batch_size`
- `outbox_publish_duration_seconds`
- `outbox_lock_wait_duration_seconds`
- `outbox_published_total`
- `outbox_retry_scheduled_total`
- `worker_messages_consumed_total`
- `worker_messages_processed_total`
- `worker_messages_failed_total`
- `worker_duplicate_events_skipped_total`
- `worker_processing_duration_seconds`
- `delivery_attempt_total`
- `provider_request_duration_seconds`
- `provider_error_total`
- `webhook_request_total`
- `webhook_retry_total`
- `admin_bff_request_duration_seconds`
- `admin_bff_downstream_error_total`

## Correlation And Tracing

All backend HTTP requests pass through a correlation filter:

- Reads `X-Correlation-Id` if present
- Generates one if missing
- Returns it in the response header
- Adds it to MDC for JSON logs
- Propagates it on `RestClient` calls

Outbox publishing forwards correlation metadata to RabbitMQ headers. Worker consumers read `X-Correlation-Id` and add it to MDC alongside `eventId`, `notificationId`, and `channel`.

Traces are exported to:

```text
http://otel-collector:4318/v1/traces
```

The collector forwards traces to Jaeger.

## Alerts

Prometheus loads `observability/prometheus/alert-rules.yml`, including alerts for:

- High API latency
- Growing outbox backlog
- Old pending outbox events
- Worker failure rate
- Provider error rate
- Dead letter events
- Admin BFF downstream errors

## Kubernetes

Apply the base platform:

```bash
kubectl apply -f k8s/base/microservices.yaml
```

Apply the observability stack:

```bash
kubectl apply -f k8s/observability/observability.yaml
```

Port-forward local access:

```bash
kubectl -n observability port-forward svc/prometheus 9090:9090
kubectl -n observability port-forward svc/grafana 3000:3000
kubectl -n observability port-forward svc/jaeger 16686:16686
```

The Kubernetes backend deployments use actuator health probes:

- Readiness: `/actuator/health/readiness`
- Liveness: `/actuator/health/liveness`

Pods also include Prometheus scrape annotations for `/actuator/prometheus`.

## Validation

Run these checks after changes:

```bash
mvn -f services/pom.xml test
mvn -f services/pom.xml package -DskipTests
docker compose -f docker-compose.microservices.yml config
```

After the stack is running, verify:

```bash
curl http://localhost:8081/actuator/health/readiness
curl http://localhost:8081/actuator/prometheus | grep notification_created_total
curl http://localhost:9090/api/v1/targets
```

Then submit a notification with an `X-Correlation-Id` header and confirm:

- The same correlation id appears in service logs
- Jaeger shows traces across API, template, preference, outbox, and worker services
- Prometheus shows outbox and worker metric movement
- Grafana dashboards populate

## Failure Scenarios

- Broker down: outbox publisher records failures and schedules retry/backoff; backlog and oldest age alerts fire.
- Worker crash: RabbitMQ redelivers unacked messages; idempotency counters show duplicate skips when already processed.
- Publisher crash after publish: duplicate broker delivery can happen; workers dedupe by event id.
- Provider failure: worker saves a failed delivery attempt and increments provider error metrics.
- Duplicate event: worker skips using `processed_events` and increments `worker_duplicate_events_skipped_total`.
