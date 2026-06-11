# Notification Platform

Learning-focused notification platform implemented as Spring Boot microservices.

The root project is a Maven workspace aggregator. Production behavior lives in `services/`; the previous single-application source tree has been removed.

## Services

| Service | Port | Owns |
| --- | ---: | --- |
| `notification-api-service` | 8081 | Notification intake, notification status, notification DB schema, outbox writes |
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
- MailHog: SMTP `localhost:1025`, UI `localhost:8025`

## Local Run

```bash
mvn test
mvn -f services/pom.xml package -DskipTests
docker compose up -d --build
```

The root `docker-compose.yml` includes `docker-compose.microservices.yml`, so `docker compose up` starts the microservices topology.

## Main Flow

1. `POST /notifications` on `notification-api-service`.
2. Notification API calls `template-service` to render and validate the template.
3. Notification API calls `preference-service` to check channel opt-in.
4. Notification API writes notification, delivery, and outbox rows in one transaction.
5. `outbox-publisher-service` locks ready events with `FOR UPDATE SKIP LOCKED`.
6. Publisher emits a RabbitMQ delivery job to the channel queue.
7. The channel worker consumes the job, deduplicates by `eventId`, calls its provider, and stores the delivery attempt.

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

- [Microservices architecture](docs/microservices-architecture.md)
- [Migration notes](docs/refactor-to-microservices.md)
- [Service workspace](services/README.md)
