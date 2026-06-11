# Agent Instructions

This project is a Spring Boot microservices notification platform.

## Important rules

- Work in small, reviewable steps.
- Keep service ownership explicit.
- Use PostgreSQL-owned schemas for local development.
- Use Redis only for cache, rate-limit, and idempotency acceleration.
- Use the outbox pattern for reliable queue publishing.
- Add tests for service logic.
- Add Flyway migrations for schema changes.
- Keep business logic out of controllers.

## Backend stack

- Java 21
- Spring Boot
- PostgreSQL
- Flyway
- JDBC
- RabbitMQ
- Validation
- Docker Compose

## Architecture principles

- Notification API owns notification request state and outbox writes.
- Template service owns template rendering and validation.
- Preference service owns user/product/channel choices.
- Outbox publisher owns polling and broker publishing.
- Workers own provider calls, idempotency, and delivery attempts.
- Provider-specific logic must be hidden behind adapters.
