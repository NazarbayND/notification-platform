# RabbitMQ to Kafka Migration

## Flags

```yaml
notification:
  broker:
    intake: kafka
    delivery: kafka
```

Kafka is now the configured default for intake and delivery. Set intake to `legacy` and delivery to `rabbitmq` for rollback. RabbitMQ code/configuration remains temporarily; remove it only after the deferred parity, resilience, and load validation has passed in the target environment.

Never dual-publish delivery work without a migration ID and cross-broker deduplication. Provider calls remain at-least-once; Kafka transactions cannot make an external provider side effect exactly-once.

## Current rollback

1. Set `NOTIFICATION_BROKER_INTAKE=legacy` on the API.
2. Set `NOTIFICATION_BROKER_DELIVERY=rabbitmq` on the orchestrator.
3. Restart the API and orchestrator.
4. Confirm template and preference services, PostgreSQL, outbox publisher, RabbitMQ, and workers are healthy.
5. Requests already accepted into Kafka remain assigned to the Kafka orchestrator group; do not replay them into the legacy path without deduplication.

Old notification API tables and RabbitMQ runtime dependencies are retained for rollback until validation and a separate removal decision.
