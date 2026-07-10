# Delivery Semantics

Kafka intake and later consumers use at-least-once delivery. Within Kafka, consume-transform-produce stages may become effectively-once or transactional in later phases. External email, SMS, push, webhook, and in-app side effects are never claimed as exactly-once.

Each command has a unique `eventId`; requests also have a tenant-scoped `idempotencyKey`. The Phase 3 Redis cache gives short-lived duplicate-request suppression, but durable deduplication belongs to the Phase 4 orchestrator database. Existing RabbitMQ workers deduplicate by event ID in channel-owned PostgreSQL tables. Providers should receive `deliveryId` as their idempotency key where supported.

`202 ACCEPTED` only confirms durable Kafka intake. The result can later become `PROCESSING`, `SCHEDULED`, `PARTIALLY_DELIVERED`, `DELIVERED`, `FAILED`, or `REJECTED`. Projection absence immediately after intake is expected eventual consistency.
