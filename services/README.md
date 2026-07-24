# Backend Workspace

`services/pom.xml` is the Maven reactor for the platform's Spring Boot services and shared modules.

## Ownership

| Module | Owns durable state |
| --- | --- |
| `notification-api-service` | None; Redis holds short-lived acceptance/idempotency data |
| `notification-orchestrator-service` | Processed requests, reference projections, durable delivery/status outbox |
| `notification-projection-service` | Rebuildable notification, delivery, and processed-event projections |
| `template-service` | Products, templates, and template event outbox |
| `preference-service` | Preferences and preference event outbox |
| Each worker | Provider attempts, deduplication, and channel-specific inbox/test data |
| `admin-bff-service` | None |

Every database-owning service uses its own PostgreSQL schema and Flyway migrations. Cross-schema reads and writes are prohibited.

## Commands

```bash
mvn -f services/pom.xml test
mvn -f services/pom.xml package -DskipTests
docker compose up -d --build
```

The service ports are listed in the root [README](../README.md).

Local provider inspection:

- Email: MailHog at `http://localhost:8025`, or `GET /test/email-messages` with the test provider.
- SMS: `GET /test/sms-messages`.
- Push: `GET /test/push-messages`.
- In-app: `GET /users/{userId}/in-app-notifications`.
- Webhook: `GET /received-webhooks`.
