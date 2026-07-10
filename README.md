# Notification Platform

Learning-focused notification platform implemented as Spring Boot microservices.

The root project is a Maven workspace aggregator. Production behavior lives in `services/`; the previous single-application source tree has been removed.

## Services

| Service | Port | Owns |
| --- | ---: | --- |
| `notification-api-service` | 8081 | Admission-controlled Kafka-first intake, acceptance status, legacy DB/outbox rollback path |
| `shared-event-contracts` | — | Versioned Kafka JSON DTOs; no persistence entities |
| `template-service` | 8082 | Template CRUD, rendering, variable validation |
| `preference-service` | 8083 | User/product/channel preferences |
| `outbox-publisher-service` | 8084 | Outbox polling, locking, RabbitMQ publishing, retry state |
| `email-worker-service` | 8085 | Email delivery attempts, SMTP/test email providers |
| `sms-worker-service` | 8086 | SMS delivery attempts and local SMS test inbox |
| `push-worker-service` | 8087 | Push delivery attempts and local push test inbox |
| `admin-bff-service` | 8088 | Admin aggregation across services |
| `in-app-worker-service` | 8089 | In-app delivery attempts and user in-app notifications |
| `webhook-worker-service` | 8090 | Webhook delivery attempts and local webhook receiver |
| `admin-frontend` | 5173 | Admin UI |

Local infrastructure:

- PostgreSQL: `localhost:5432`
- RabbitMQ: `localhost:5672`, management UI `localhost:15672`
- Redis: `localhost:6379`
- Kafka: `localhost:9092`, Kafka UI `localhost:8080`
- MailHog: SMTP `localhost:1025`, UI `localhost:8025`

## Local Run

```bash
mvn test
mvn -f services/pom.xml package -DskipTests
docker compose up -d --build
```

The root `docker-compose.yml` includes `docker-compose.microservices.yml`, so `docker compose up` starts the microservices topology.

## Main Flow (Phases 1–3)

1. `POST /notifications` on `notification-api-service`.
2. The API validates the body and applies Redis-backed global/per-tenant rates plus a local concurrency cap.
3. The API publishes `NotificationRequested` to `notification.requests.v1` and waits for an `acks=all` acknowledgement within a finite deadline.
4. The API stores short-lived Redis acceptance/idempotency state and returns `202 Accepted`.
5. `ACCEPTED` means Kafka durably accepted the command; rendering and delivery have not happened yet.

The Phase 4 orchestrator is the next migration step. Until it exists, Kafka requests are durable but are not delivered. Set `NOTIFICATION_BROKER_INTAKE=legacy` to use the previous synchronous template/preference/PostgreSQL outbox path and existing RabbitMQ workers.

```json
{
  "notificationId": "3a6c5b82-...",
  "requestId": "6948c028-...",
  "status": "ACCEPTED",
  "acceptedAt": "2026-07-10T12:00:00Z"
}
```

## Useful Endpoints

Notification API:

- `POST http://localhost:8081/notifications`
- `GET http://localhost:8081/notifications`
- `GET http://localhost:8081/notifications/{id}`
- `GET http://localhost:8081/notifications/{id}/status`

Templates:

- `GET http://localhost:8082/templates`
- `POST http://localhost:8082/templates`
- `PUT http://localhost:8082/templates/{id}`
- `POST http://localhost:8082/templates/{id}/preview`
- `POST http://localhost:8082/templates/render`

Preferences:

- `GET http://localhost:8083/preferences`
- `POST http://localhost:8083/preferences`
- `PUT http://localhost:8083/preferences/{id}`
- `GET http://localhost:8083/preferences/check`

Outbox:

- `GET http://localhost:8084/outbox/events`
- `POST http://localhost:8084/outbox/events/poll`
- `POST http://localhost:8084/outbox/events/{id}/retry`

Test inboxes:

- MailHog UI: `http://localhost:8025`
- SMS: `GET http://localhost:8086/test/sms-messages`
- Push: `GET http://localhost:8087/test/push-messages`
- In-app: `GET http://localhost:8089/test/in-app-notifications`
- Webhook receiver: `POST http://localhost:8090/webhooks/test`
- Webhook inbox: `GET http://localhost:8090/received-webhooks`

Admin BFF:

- `GET http://localhost:8088/admin/dashboard/stats`
- `GET http://localhost:8088/admin/notifications`
- `GET http://localhost:8088/admin/outbox-events`
- `GET http://localhost:8088/admin/templates`
- `GET http://localhost:8088/admin/preferences`
- `GET http://localhost:8088/admin/test/sms-messages`
- `GET http://localhost:8088/admin/test/push-messages`
- `GET http://localhost:8088/admin/test/in-app-notifications`
- `GET http://localhost:8088/admin/test/webhook-requests`

## Documentation

- [Kafka-first migration plan](docs/kafka-first-migration-plan.md)
- [Kafka-first architecture](docs/kafka-first-architecture.md)
- [Migration baseline](docs/kafka-migration-baseline.md)
- [Backpressure and admission control](docs/backpressure-and-admission-control.md)
- [Event contracts](docs/event-contracts.md)
- [Local Kafka runbook](docs/local-run-kafka.md)
- [RabbitMQ migration and rollback](docs/rabbitmq-to-kafka-migration.md)
- [Microservices architecture](docs/microservices-architecture.md)
- [Migration notes](docs/refactor-to-microservices.md)
- [Service workspace](services/README.md)
