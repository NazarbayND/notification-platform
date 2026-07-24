# Repository Development Guide

## Boundaries

- `notification-api-service` owns HTTP intake, admission control, Kafka publication, and short-lived Redis acceptance state. It does not own notification tables.
- `notification-orchestrator-service` owns workflow state, reference projections, and its transactional Kafka outbox.
- `notification-projection-service` owns the query model.
- Template, preference, and worker services own only their respective PostgreSQL schemas.
- Never add cross-schema reads or writes. Communicate through the documented Kafka contracts or public HTTP APIs.

## Persistence and events

- Add forward-only Flyway migrations for schema changes; never edit an applied migration.
- Keep event DTOs in `shared-event-contracts` and increment schema versions deliberately.
- Preserve stable partition keys and idempotency identifiers.
- PostgreSQL is durable state; Redis is an accelerator and must not be the only permanent record.
- Use a service-owned transactional outbox whenever one transaction must change database state and publish an event.

## Delivery

- Put provider behavior behind the channel adapter.
- Assume at-least-once delivery and make duplicate handling explicit.
- Use `worker-kafka-support` for retry, DLQ, concurrency, rate limiting, result publication, and processed-event coordination.

## Verification

Before merging backend changes, run:

```bash
mvn -f services/pom.xml test
mvn -f services/pom.xml package -DskipTests
```

For frontend or deployment changes, also run:

```bash
npm --prefix frontend run lint
npm --prefix frontend run build
docker compose config
```
