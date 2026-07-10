# RabbitMQ to Kafka Migration

## Flags

```yaml
notification:
  broker:
    intake: kafka
    delivery: rabbitmq
```

Set intake to `legacy` for immediate rollback. Phase 4 keeps RabbitMQ delivery while adding the orchestrator. Phase 6 migrates workers one at a time, starting with email. Kafka delivery becomes the default only after parity, integration, resilience, and load tests pass. RabbitMQ code/configuration remains temporarily for rollback.

Never dual-publish delivery work without a migration ID and cross-broker deduplication. Provider calls remain at-least-once; Kafka transactions cannot make an external provider side effect exactly-once.

## Current rollback

1. Set `NOTIFICATION_BROKER_INTAKE=legacy` on the API.
2. Restart the API.
3. Confirm template and preference services, PostgreSQL, outbox publisher, RabbitMQ, and workers are healthy.
4. Requests already accepted into Kafka remain there for Phase 4 processing; do not replay them into the legacy path without deduplication.

No old tables or RabbitMQ runtime dependency has been removed in Phases 1–3.
