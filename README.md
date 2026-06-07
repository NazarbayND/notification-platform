# Notification Platform

Senior-level notification platform MVP built as a simple modular monolith with Spring Boot, PostgreSQL, Flyway, and Spring Data JPA.

The platform accepts product-scoped notification requests, creates durable request and delivery records, and uses the outbox pattern so queue publishing can be retried safely.

## Current Status

Implemented:

- Flyway schema for products, templates, preferences, batches, notification requests, deliveries, and outbox events
- JPA entities and enums for the MVP schema
- Repository layer for all current aggregate tables
- Service layer for product/template management
- Service layer for user preferences
- Service layer for notification and batch submission
- Service layer for delivery retry/DLQ state transitions
- Service layer for outbox polling and publish status updates
- REST controllers for the MVP API surface
- Request/response DTOs and global API error handling
- Focused unit tests for service logic
- Focused MVC tests for representative controller behavior

Not implemented yet:

- Provider adapters
- Queue implementation
- Worker processes
- Docker Compose
- Integration tests with PostgreSQL

## Stack

- Java 21
- Spring Boot 3.3
- Spring Data JPA
- PostgreSQL
- Flyway
- Bean Validation
- JUnit 5 / Mockito

## Architecture

The MVP keeps the system as a modular monolith. PostgreSQL is the source of truth.

Main flow:

1. Product services submit notification requests.
2. Notification service validates the request and checks product-scoped idempotency.
3. The service resolves an active template and checks user preferences.
4. The service stores the notification request, delivery row, and outbox event in one transaction.
5. An outbox publisher will later publish pending outbox events to priority queues.
6. Channel workers will later consume delivery work and call provider adapters.
7. Failed deliveries retry with backoff and move to DLQ after max attempts.

See [ARCHITECTURE.md](ARCHITECTURE.md) for schema details, relationships, and index explanations.

## Data Model

Core tables:

- `products`
- `notification_templates`
- `user_notification_preferences`
- `notification_batches`
- `notification_requests`
- `notification_deliveries`
- `delivery_attempts`
- `outbox_events`

Important data rules:

- The platform does not own full end-user profiles in the MVP.
- Product teams own users; this platform stores product-scoped `external_user_id`.
- Idempotency keys are unique per product.
- Notification requests store template intent through `template_key` and `requested_channels`.
- Notification deliveries store the resolved `template_id`.
- Request status is separate from delivery status.
- Delivery attempts are stored separately from current delivery state.
- Provider-specific details belong on delivery records or provider adapters.
- Redis is optional later for cache, rate limiting, or idempotency optimization only.

## Project Layout

```text
src/main/java/com/notificationplatform
├── NotificationPlatformApplication.java
├── application
│   ├── common
│   ├── delivery
│   ├── management
│   ├── notification
│   ├── outbox
│   └── preferences
└── domain
    ├── common
    ├── entity
    ├── model
    └── repository

src/main/resources
├── application.yml
└── db/migration
    └── V1__create_notification_platform_schema.sql
```

## Configuration

Default database settings are in `src/main/resources/application.yml`.

Environment variables:

```text
DATABASE_URL=jdbc:postgresql://localhost:5432/notification_platform
DATABASE_USERNAME=notification
DATABASE_PASSWORD=notification
```

Hibernate is configured with `ddl-auto: validate`; Flyway owns schema creation.

## Local Commands

Run tests:

```bash
mvn test
```

Run the application:

```bash
mvn spring-boot:run
```

The current environment used to generate this project did not have Maven installed, so tests were added but could not be executed here.

## Next Steps

Recommended next small steps:

1. Add Docker Compose for PostgreSQL.
2. Run Flyway against a local database and fix any validation issues.
3. Add integration tests for repositories and migrations.
4. Add mock email provider adapter and email worker.
5. Add outbox publisher loop.
6. Add queue configuration.
7. Add API integration tests.
