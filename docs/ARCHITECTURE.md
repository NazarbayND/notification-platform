# Notification Platform Architecture

## Goal

Multi-channel notification platform for product teams.

## Main flow

Product services send notification requests to Notification API.
Notification API validates request, stores notification request, delivery rows, and outbox event in PostgreSQL in one transaction.
Outbox Publisher publishes events to priority queues.
Channel workers consume messages and send through provider adapters.

## Components

- Notification API
- Management API
- PostgreSQL
- Redis
- Outbox Publisher
- Priority Queues
- Email Worker
- SMS Worker
- Push Worker
- In-App Worker
- Provider Adapters

## Reliability requirements

- Idempotency key is required for notification and batch creation.
- Queue publishing must use outbox pattern.
- Workers must be idempotent.
- Failed deliveries must retry with exponential backoff.
- After max retries, delivery goes to DLQ.
- Expired notification requests and deliveries must not be sent.

## Status enums

Status values are stored as strings in PostgreSQL and mapped with Java enums.

### ProductStatus

- `ACTIVE`
- `DISABLED`

### TemplateStatus

- `DRAFT`
- `ACTIVE`
- `ARCHIVED`

### BatchStatus

- `ACCEPTED`
- `PROCESSING`
- `COMPLETED`
- `PARTIAL_FAILED`
- `FAILED`

### NotificationRequestStatus

- `ACCEPTED` - request was accepted and persisted
- `DELIVERY_CREATED` - one or more delivery rows were created
- `COMPLETED` - all non-skipped deliveries reached a terminal success state
- `PARTIAL_FAILED` - request has multiple deliveries and at least one failed terminally
- `FAILED` - request failed terminally
- `SKIPPED` - request produced no sendable deliveries, usually because preferences disabled all requested channels or the request expired

### DeliveryStatus

- `PENDING` - delivery is ready for worker pickup
- `PROCESSING` - worker has locked the delivery for an attempt
- `SENT` - provider accepted the message
- `DELIVERED` - provider confirmed final delivery
- `FAILED` - non-retryable failure
- `RETRY_SCHEDULED` - retry is scheduled through `next_attempt_at`
- `DLQ` - max attempts exhausted
- `SKIPPED` - delivery was intentionally not sent

### DeliveryAttemptStatus

- `STARTED`
- `SUCCEEDED`
- `FAILED`

### OutboxEventStatus

- `PENDING`
- `PUBLISHED`
- `FAILED`

## MVP data model

PostgreSQL is the source of truth. Redis may be added later only for cache, rate limiting, or idempotency optimization.

The platform does not own full end-user profiles in the MVP. Product teams own users. The notification platform stores product-scoped `external_user_id` values, contact details supplied on notification requests, user notification preferences, request history, delivery history, and outbox state.

### Products

`products` identifies product teams or applications using the platform.

- `id`
- `name`
- `status`
- `created_at`
- `updated_at`

### Templates

`notification_templates` stores product-scoped, channel-specific template versions.

- `id`
- `product_id`
- `template_key`
- `channel`
- `version`
- `subject`
- `content`
- `status`
- `created_at`
- `updated_at`

Only one active template is allowed for a given `(product_id, template_key, channel)`.

### User Preferences

`user_notification_preferences` stores opt-in or opt-out decisions per product, user, category, and channel.

- `id`
- `product_id`
- `external_user_id`
- `category`
- `channel`
- `enabled`
- `created_at`
- `updated_at`

Unique preference key: `(product_id, external_user_id, category, channel)`.

### Notification Batches

`notification_batches` tracks batch submission state for `POST /api/v1/notification-batches`.

- `id`
- `product_id`
- `idempotency_key`
- `status`
- `total_count`
- `accepted_count`
- `failed_count`
- `created_at`
- `updated_at`

Idempotency key is unique per product.

### Notification Requests

`notification_requests` represents the original request accepted by the Notification API. Request status is orchestration-level state, not provider delivery state.
It stores the requested template key and channel intent. Concrete template versions are resolved per delivery.

- `id`
- `product_id`
- `batch_id`
- `template_key`
- `requested_channels`
- `external_user_id`
- `idempotency_key`
- `category`
- `priority`
- `payload`
- `recipient`
- `status`
- `expires_at`
- `created_at`
- `updated_at`

Idempotency key is unique per product.

### Notification Deliveries

`notification_deliveries` represents one channel delivery created from a notification request. Delivery status, retry state, provider response data, and DLQ state live here.
Each delivery stores the concrete `template_id` resolved for its channel, so future template changes do not affect already-created deliveries.

- `id`
- `notification_request_id`
- `template_id`
- `channel`
- `provider`
- `destination`
- `status`
- `attempt_count`
- `max_attempts`
- `next_attempt_at`
- `locked_until`
- `provider_message_id`
- `last_error_code`
- `last_error_message`
- `provider_response`
- `sent_at`
- `delivered_at`
- `failed_at`
- `expires_at`
- `created_at`
- `updated_at`

The MVP allows one delivery per `(notification_request_id, channel)`.

### Delivery Attempts

`delivery_attempts` records each worker/provider attempt for a delivery. The delivery row stores current state; attempt rows store history.

- `id`
- `notification_delivery_id`
- `attempt_number`
- `status`
- `provider`
- `provider_message_id`
- `error_code`
- `error_message`
- `request_payload`
- `response_payload`
- `started_at`
- `completed_at`
- `created_at`

Attempt number is unique per delivery.

### Outbox Events

`outbox_events` stores durable events created in the same database transaction as domain changes. The Outbox Publisher polls pending events and marks them published after successful queue publication.

- `id`
- `aggregate_type`
- `aggregate_id`
- `event_type`
- `payload`
- `status`
- `available_at`
- `published_at`
- `attempt_count`
- `last_error`
- `created_at`
- `updated_at`

## Important indexes

Indexes are chosen around the MVP write and read paths: product-scoped idempotency checks, preference lookup during notification creation, user notification history, worker polling, and outbox publishing.

- `products(name)` unique

  Prevents duplicate product records and supports management lookups by product name.

- `notification_templates(product_id, template_key, channel, version)` unique

  Prevents duplicate template versions for the same product, template key, and channel. This keeps template rendering deterministic when a request references a specific template version.

- `notification_templates(product_id, template_key, channel)` unique where status is active

  Ensures there is only one active template for a product/template/channel combination. This supports the common send path where the API resolves the currently active template.

- `user_notification_preferences(product_id, external_user_id, category, channel)` unique

  Guarantees one preference decision for a product user, notification category, and channel. This supports fast preference lookup while creating deliveries and avoids conflicting opt-in/opt-out rows.

- `user_notification_preferences(product_id, external_user_id)`

  Supports `GET /api/v1/users/{userId}/preferences` by loading all preferences for a product-scoped user.

- `notification_batches(product_id, idempotency_key)` unique

  Enforces idempotent batch creation per product. A retried batch request with the same key can return the original batch instead of creating duplicate notifications.

- `notification_batches(product_id, created_at)`

  Supports management and operational views that list recent batches for a product.

- `notification_requests(product_id, idempotency_key)` unique

  Enforces idempotent notification creation per product. A retried notification request with the same key can return the original request and avoid duplicate deliveries.

- `notification_requests(product_id, external_user_id, created_at)`

  Supports user notification history queries, including `GET /api/v1/notifications/{id}` authorization checks when requests are scoped by product and user.

- `notification_requests(status, created_at)`

  Supports operational queries for stuck, failed, or recently accepted requests.

- `notification_requests(batch_id)`

  Supports batch progress calculation and loading all requests created by a batch.

- `notification_deliveries(notification_request_id)`

  Supports loading deliveries for a notification request and calculating request-level status from delivery-level state.

- `notification_deliveries(next_attempt_at, expires_at, created_at)` partial where status is `PENDING` or `RETRY_SCHEDULED`

  Supports worker polling for pending and retryable, non-expired deliveries. The partial index stays smaller by indexing only rows workers can pick up.

- `notification_deliveries(provider, provider_message_id)`

  Supports provider callback or webhook correlation when a provider returns an external message id.

- `delivery_attempts(notification_delivery_id, attempt_number)` unique

  Prevents duplicate attempt numbers for the same delivery and supports attempt history views.

- `outbox_events(available_at, created_at)` partial where status is `PENDING`

  Supports the Outbox Publisher polling pending events in deterministic order, including delayed retry through `available_at`. The partial index avoids indexing published event history.

- `outbox_events(aggregate_type, aggregate_id)`

  Supports troubleshooting and replay workflows by finding all events emitted for a specific aggregate, such as a notification request or delivery.
