# Microservices Workspace

This directory contains the independently runnable Spring Boot services for the Notification Platform. Each service owns one bounded context, its persistence schema, and its runtime container image.

Services:

- `notification-api-service`: accepts notification requests, validates payloads, owns notification and delivery request state, and writes outbox events in the same transaction.
- `template-service`: owns templates, template validation, and render preview.
- `preference-service`: owns user/product notification preferences and preference checks.
- `outbox-publisher-service`: polls outbox events with locking and publishes delivery jobs.
- `email-worker-service`: consumes email jobs, sends through an email provider adapter, and records attempts.
- `sms-worker-service`: consumes SMS jobs, sends through an SMS provider adapter, and records attempts.
- `push-worker-service`: consumes push jobs, sends through a push provider adapter, and records attempts.
- `in-app-worker-service`: consumes in-app jobs, stores in-app notifications, and exposes read/unread APIs.
- `webhook-worker-service`: consumes webhook jobs and provides a local webhook receiver for test mode.
- `admin-bff-service`: aggregates admin-facing data from backend services for the admin UI.

Local ports used by `docker-compose.microservices.yml`:

| Service | Port |
| --- | ---: |
| notification-api-service | 8081 |
| template-service | 8082 |
| preference-service | 8083 |
| outbox-publisher-service | 8084 |
| email-worker-service | 8085 |
| sms-worker-service | 8086 |
| push-worker-service | 8087 |
| admin-bff-service | 8088 |
| in-app-worker-service | 8089 |
| webhook-worker-service | 8090 |
| admin-frontend | 5173 |
| MailHog UI | 8025 |

Build the service jars before building the service images:

```bash
mvn -f services/pom.xml package -DskipTests
docker compose up -d --build
```

The service workspace can be checked independently:

```bash
mvn -f services/pom.xml test
```

Local test inboxes:

- Email: MailHog UI at `http://localhost:8025`, plus `GET /test/email-messages` when `EMAIL_PROVIDER=test`.
- SMS: `GET /test/sms-messages`, `GET /test/sms-messages/{id}`, `DELETE /test/sms-messages`.
- Push: `GET /test/push-messages`, `GET /test/push-messages/{id}`, `DELETE /test/push-messages`.
- In-app: `GET /users/{userId}/in-app-notifications`, `POST /users/{userId}/in-app-notifications/{id}/read`, `GET /test/in-app-notifications`, `DELETE /test/in-app-notifications`.
- Webhook: `POST /webhooks/test`, `GET /received-webhooks`, `DELETE /received-webhooks`.
