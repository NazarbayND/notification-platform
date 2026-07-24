# Kubernetes Configuration

The manifests are learning-oriented and are not production-ready defaults.

## Files

- `k8s/base/microservices.yaml`: namespace, shared configuration, PostgreSQL, Redis, MailHog, APIs, workers, BFF, frontend, Services, and CPU HPAs.
- `k8s/base/event-services.yaml`: orchestrator and projection deployments.
- `k8s/base/keda-kafka-scalers.yaml`: Kafka-lag `ScaledObject` resources; requires KEDA.
- `k8s/observability/observability.yaml`: Prometheus, Grafana, Loki, Jaeger, Promtail, and OpenTelemetry Collector.
- `k8s/observability/grafana-dashboards.yaml`: current dashboards.

Application workloads use the `notification-platform` namespace; observability uses `observability`.

## Kafka

Kafka is not installed by these manifests. The default endpoint is:

```text
kafka-kafka-bootstrap.kafka.svc:9092
```

Install Kafka with Strimzi, another operator, or a managed service and override `SPRING_KAFKA_BOOTSTRAP_SERVERS`. Production configuration should use replication, appropriate minimum ISR, TLS/SASL, quotas, and measured partition counts.

## Configuration and secrets

`notification-platform-config` contains service URLs, Kafka/Redis endpoints, intake limits, tracing settings, actuator exposure, and small-cluster Hikari pool limits.

`notification-platform-secrets` contains the local PostgreSQL credentials. Replace plain manifest credentials with an external or sealed secret mechanism outside a disposable environment.

Each database-owning deployment points Flyway and JDBC at its own PostgreSQL schema. The notification API and admin BFF have no datasource configuration.

## Apply

Build or load the `notification-platform/*:local` images into the cluster, then:

```bash
kubectl apply -f k8s/base/microservices.yaml
kubectl apply -f k8s/base/event-services.yaml
kubectl apply -f k8s/base/keda-kafka-scalers.yaml
kubectl apply -f k8s/observability/observability.yaml
kubectl apply -f k8s/observability/grafana-dashboards.yaml
```

Install metrics-server before relying on CPU HPAs and install KEDA before applying its CRDs.

Check the deployment:

```bash
kubectl -n notification-platform get pods,svc
kubectl -n notification-platform get hpa,scaledobject
kubectl -n observability get pods,svc
```

## Local access

```bash
kubectl -n notification-platform port-forward svc/admin-frontend 5173:80
kubectl -n notification-platform port-forward svc/mailhog 8025:8025
kubectl -n observability port-forward svc/prometheus 9090:9090
kubectl -n observability port-forward svc/grafana 3000:3000
kubectl -n observability port-forward svc/jaeger 16686:16686
```

## Production gaps

- PostgreSQL is a single ephemeral pod with no backup/PVC policy.
- Redis is a single pod and Kafka is external.
- Credentials and traffic are plaintext.
- Images are local tags.
- NetworkPolicy, PodDisruptionBudget, topology spread, ingress/authentication, and backup/restore procedures are absent.
- Replica and scaling limits are examples, not benchmark-derived production values.
