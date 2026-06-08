# Notification Platform

Senior-level notification platform MVP built as a simple modular monolith with Spring Boot, PostgreSQL, Flyway, Spring Data JPA, RabbitMQ, and local SMTP testing through MailHog.

The platform accepts product-scoped notification requests, creates durable request and delivery records, and uses the outbox pattern so RabbitMQ publishing can be retried safely.

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
- RabbitMQ-backed priority queues for async email delivery work
- Mock email provider adapter
- MailHog SMTP email provider for local testing
- RabbitMQ email worker with manual acknowledgements
- REST controllers for the MVP API surface
- Request/response DTOs and global API error handling
- Focused unit tests for service logic
- Focused MVC tests for representative controller behavior

Not implemented yet:

- Real email providers such as SES or SendGrid
- Integration tests with PostgreSQL

## Stack

- Java 21
- Spring Boot 3.3
- Spring Data JPA
- PostgreSQL
- RabbitMQ
- MailHog
- Flyway
- Spring Mail
- Bean Validation
- JUnit 5 / Mockito

## Architecture

The MVP keeps the system as a modular monolith. PostgreSQL is the source of truth.

Main flow:

1. Product services submit notification requests.
2. Notification service validates the request and checks product-scoped idempotency.
3. The service resolves an active template and checks user preferences.
4. The service stores the notification request, delivery row, and outbox event in one transaction.
5. An outbox publisher publishes pending outbox events to RabbitMQ priority queues.
6. Channel workers consume delivery work and call provider adapters.
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
│   ├── preferences
│   ├── provider
│   ├── queue
│   └── worker
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

Default database, RabbitMQ, and mock email provider settings are in `src/main/resources/application.yml`.
Local MailHog SMTP settings are in `src/main/resources/application-local.yml`.

Environment variables:

```text
DATABASE_URL=jdbc:postgresql://localhost:5432/notification_platform
DATABASE_USERNAME=notification
DATABASE_PASSWORD=notification
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=notification
RABBITMQ_PASSWORD=notification
NOTIFICATION_EMAIL_FROM=no-reply@notification-platform.local
```

Hibernate is configured with `ddl-auto: validate`; Flyway owns schema creation.

The default email provider is `mock`. Run with the `local` Spring profile to use MailHog SMTP:

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

## Local Infrastructure

Start PostgreSQL, RabbitMQ, and MailHog:

```bash
docker compose up -d
```

RabbitMQ endpoints:

```text
AMQP: localhost:5672
Management UI: http://localhost:15672
Username: notification
Password: notification
```

MailHog endpoints:

```text
SMTP: localhost:1025
Web UI: http://localhost:8025
```

The application declares a durable direct exchange:

```text
notifications.exchange
```

Queues:

```text
notifications.high.email
notifications.normal.email
notifications.low.email
notifications.retry.email
notifications.dlq.email
```

Routing keys:

```text
notification.high.email
notification.normal.email
notification.low.email
notification.retry.email
notification.dlq.email
```

The retry queue is a delay holding queue. Workers publish retry messages to `notifications.retry.email` with a per-message TTL based on delivery backoff. When the TTL expires, RabbitMQ dead-letters the message back to the normal email route for processing. Delivery status checks keep duplicate messages idempotent.

## Manual Email Test

Start infrastructure:

```bash
docker compose up -d
```

Run the app with MailHog enabled:

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

Open MailHog:

```text
http://localhost:8025
```

Create a product:

```bash
curl -s -X POST http://localhost:8080/api/v1/admin/products \
  -H "Content-Type: application/json" \
  -d '{"name":"Billing"}'
```

Use the returned `id` as `PRODUCT_ID`, then create a sample email template:

```bash
curl -s -X POST http://localhost:8080/api/v1/admin/templates \
  -H "Content-Type: application/json" \
  -d '{
    "productId": "PRODUCT_ID",
    "templateKey": "billing.invoice.ready",
    "channel": "EMAIL",
    "version": 1,
    "subject": "Invoice ready",
    "content": "Hello, your invoice is ready for review.",
    "status": "ACTIVE"
  }'
```

Send a test notification:

```bash
curl -s -X POST http://localhost:8080/api/v1/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "productId": "PRODUCT_ID",
    "templateKey": "billing.invoice.ready",
    "requestedChannels": ["EMAIL"],
    "externalUserId": "manual-user-1",
    "idempotencyKey": "manual-email-test-1",
    "category": "billing",
    "priority": "NORMAL",
    "payload": {
      "name": "Ada"
    },
    "recipient": {
      "email": "ada@example.com"
    },
    "expiresAt": null
  }'
```

The outbox publisher sends the delivery message to RabbitMQ, the email worker sends through MailHog SMTP, and the email should appear in the MailHog UI.

## Local Commands

Run tests:

```bash
mvn test
```

Run the application:

```bash
mvn spring-boot:run
```

Run with MailHog:

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

## Next Steps

Recommended next small steps:

1. Add integration tests for repositories and migrations.
2. Add RabbitMQ integration tests with Testcontainers.
3. Add real provider adapters behind the existing provider interface.
4. Add API integration tests.
