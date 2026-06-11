# Agent Instructions

You are helping build a Senior-level Notification Platform project.

## Important rules

- Do not implement everything at once.
- Work in small, reviewable steps.
- Prefer simple modular monolith first.
- Do not introduce microservices unless asked.
- Do not introduce Kafka unless asked.
- Use PostgreSQL as source of truth.
- Use Redis only for cache/rate-limit/idempotency optimization.
- Use outbox pattern for reliable queue publishing.
- Add tests for service logic.
- Add Flyway migrations for schema changes.
- Keep business logic out of controllers.

## Backend stack

- Java 21
- Spring Boot
- PostgreSQL
- Flyway
- Spring Data JPA
- Validation
- Docker Compose

## Architecture principles

- Separate Notification API and Management API logically.
- Separate NotificationRequest from NotificationDelivery.
- Workers must be idempotent.
- Provider-specific logic must be hidden behind adapters.
