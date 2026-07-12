# Kubernetes Configuration

## Kafka endpoint

The application manifests intentionally do not install a production Kafka cluster. The base ConfigMap points at a conventional Strimzi bootstrap service:

```text
kafka-kafka-bootstrap.kafka.svc:9092
```

Install/manage Kafka separately with Strimzi, Bitnami, or a managed Kafka provider, then override `SPRING_KAFKA_BOOTSTRAP_SERVERS`. Kafka is the default intake and delivery broker; RabbitMQ remains deployed for the explicit rollback mode.

This project uses plain Kubernetes manifests for local learning and testing.

## Files

- `k8s/base/microservices.yaml` deploys the application platform.
- `k8s/base/kafka-migration-services.yaml` deploys the orchestrator and projection service.
- `k8s/base/keda-kafka-scalers.yaml` defines Kafka-lag scaling and requires KEDA CRDs.
- `k8s/observability/observability.yaml` deploys Prometheus, Grafana, Loki, Jaeger, Promtail, and the OpenTelemetry Collector.

## Namespace

Application workloads run in:

```text
notification-platform
```

Observability workloads run in:

```text
observability
```

## Platform Components

`k8s/base/microservices.yaml` includes:

- Infrastructure: `postgres`, `rabbitmq`, `redis`, `mailhog`
- API services: `notification-api-service`, `template-service`, `preference-service`
- Background services: `notification-orchestrator-service`, `notification-projection-service`, and legacy `outbox-publisher-service`
- Workers: `email-worker-service`, `sms-worker-service`, `push-worker-service`, `in-app-worker-service`, `webhook-worker-service`
- Admin: `admin-bff-service`, `admin-frontend`

Each application service has a `Deployment`, `Service`, resource requests/limits, and readiness/liveness probes. Kafka consumers use KEDA lag scalers; non-consumer workloads retain CPU HPAs where configured.

## Configuration

Shared non-secret values are stored in `notification-platform-config`.

Important values:

- Service URLs, for example `NOTIFICATION_API_URL`, `TEMPLATE_SERVICE_URL`, `OUTBOX_PUBLISHER_SERVICE_URL`
- Broker and cache hosts: `RABBITMQ_HOST`, `REDIS_HOST`
- Outbox tuning: `OUTBOX_BATCH_SIZE`, `OUTBOX_FIXED_DELAY_MS`
- Observability endpoints and actuator settings
- Hikari pool limits for small local clusters

Secrets are stored in `notification-platform-secrets`.

Current local secrets include:

- `POSTGRES_USER`
- `POSTGRES_PASSWORD`
- `RABBITMQ_USER`
- `RABBITMQ_PASSWORD`

These values are for local development only. Use a real secret manager or sealed/external secrets for shared or production clusters.

## Health And Metrics

Spring services expose:

- Readiness: `/actuator/health/readiness`
- Liveness: `/actuator/health/liveness`
- Metrics: `/actuator/prometheus`

The frontend uses `/` for readiness and liveness.

Pods include Prometheus scrape annotations where applicable.

## Local Kind Run

Apply the platform:

```bash
kubectl apply -f k8s/base/microservices.yaml
kubectl apply -f k8s/base/kafka-migration-services.yaml
kubectl apply -f k8s/base/keda-kafka-scalers.yaml
```

Apply observability:

```bash
kubectl apply -f k8s/observability/observability.yaml
```


Check status:

```bash
kubectl -n notification-platform get pods
kubectl -n notification-platform get svc
kubectl -n notification-platform get hpa,scaledobject
```

## Local Access

Admin UI:

```bash
kubectl -n notification-platform port-forward svc/admin-frontend 5173:80
```

Open:

```text
http://localhost:5173
```

RabbitMQ management:

```bash
kubectl -n notification-platform port-forward svc/rabbitmq 15672:15672
```

Mailhog:

```bash
kubectl -n notification-platform port-forward svc/mailhog 8025:8025
```

Prometheus, Grafana, and Jaeger:

```bash
kubectl -n observability port-forward svc/prometheus 9090:9090
kubectl -n observability port-forward svc/grafana 3000:3000
kubectl -n observability port-forward svc/jaeger 16686:16686
```

## Notes

- The manifests use local image tags such as `notification-platform/admin-frontend:local`; load or build those images into the cluster before applying.
- Replicas and scaling minimums are set low for local kind usage.
- Install KEDA before applying Kafka `ScaledObject` resources; install metrics-server for CPU HPAs.
- The local Postgres deployment does not define persistent volumes, so data can be lost when the pod is recreated.
