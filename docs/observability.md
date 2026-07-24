# Observability

Every backend exposes:

- Prometheus metrics at `/actuator/prometheus`;
- readiness and liveness at `/actuator/health/readiness` and `/actuator/health/liveness`;
- structured JSON logs on stdout;
- OTLP HTTP traces through the OpenTelemetry Collector to Jaeger.

## Local stack

```bash
docker compose up -d --build
```

- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000` (`admin` / `admin` locally)
- Jaeger: `http://localhost:16686`
- Loki: `http://localhost:3100`

Grafana loads the current overview, worker, and admin BFF dashboards. The overview uses Kafka intake, orchestrator outbox, worker, provider, and DLQ metrics; it does not depend on removed broker or API-outbox metrics.

## Important metrics

Intake:

- `notification_intake_requests_total`
- `notification_intake_accepted_total`
- `notification_intake_rejected_total`
- `notification_intake_rate_limited_total`
- `notification_intake_kafka_publish_latency`
- `notification_intake_kafka_publish_failures_total`

Orchestration and projection:

- `orchestrator_requests_processed_total`
- `orchestrator_generated_deliveries_total`
- `orchestrator_rejected_notifications_total`
- `orchestrator_outbox_published_total`
- `orchestrator_outbox_failures_total`
- `projection_update_latency`

Workers/providers:

- `worker_messages_consumed_total`
- `worker_messages_processed_total`
- `worker_messages_failed_total`
- `worker_duplicate_events_skipped_total`
- `worker_processing_duration_seconds`
- `worker_retries_total`
- `worker_dlq_total`
- `worker_active_tasks`
- `delivery_attempt_total`
- `provider_request_duration_seconds`
- `provider_error_total`

Admin:

- `admin_bff_request_duration_seconds`
- `admin_bff_downstream_error_total`

## Correlation

The shared HTTP filter accepts or creates `X-Correlation-Id`, returns it in the response, and places it in MDC. Kafka contracts carry stable event, notification, delivery, tenant, and recipient identifiers. Use those IDs and the Kafka key to correlate asynchronous work; an HTTP trace cannot represent the entire delayed retry lifetime.

## Alerts

Prometheus alerts cover API p95 latency, worker/provider failure ratios, worker DLQ events, orchestrator outbox publication failures, and repeated BFF downstream errors. Validate label names against a running deployment before treating an alert as production-ready, and add Kafka consumer lag and projection-staleness alerts in a real environment.

## Verification

```bash
docker compose config
curl http://localhost:8081/actuator/health/readiness
curl http://localhost:8081/actuator/prometheus
curl http://localhost:9090/api/v1/targets
```

After sending a notification, confirm that intake/orchestrator/worker counters move, the projection reaches a terminal status, JSON logs contain stable identifiers, and Jaeger receives spans.
