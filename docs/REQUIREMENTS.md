# Requirements

## MVP

- Create product
- Create versioned channel-specific template
- Configure user preferences through API
- Send notification with product-scoped idempotency key
- Send notification batch with product-scoped idempotency key
- Create notification deliveries
- Track delivery status
- Email worker with mock provider
- Retry failed delivery
- DLQ after max retries
- Store outbox events in the same transaction as notification state changes
- Store requested channels on notification requests.
- Store resolved template version on each notification delivery.
- Store delivery attempt history.
- Support request and delivery expiration through `expires_at`.

## Data rules

- PostgreSQL is the source of truth.
- The platform stores product-scoped `external_user_id`; it does not own full end-user profiles in the MVP.
- User preferences are scoped by product, external user id, category, and channel.
- Notification request status is separate from notification delivery status.
- Notification requests store `template_key`; deliveries store the resolved `template_id`.
- Provider-specific data belongs on delivery records or provider adapters, not on request records.
- Redis is optional and only for cache, rate-limit, or idempotency optimization.

## API

- POST /api/v1/notifications
- POST /api/v1/notification-batches
- GET /api/v1/notifications/{id}
- GET /api/v1/admin/templates
- POST /api/v1/admin/templates
- GET /api/v1/admin/products
- POST /api/v1/admin/products
- GET /api/v1/users/{userId}/preferences
- PUT /api/v1/users/{userId}/preferences
