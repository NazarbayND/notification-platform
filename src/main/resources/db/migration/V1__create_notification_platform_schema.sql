CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(120) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_products_name UNIQUE (name),
    CONSTRAINT chk_products_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE notification_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products (id),
    template_key VARCHAR(120) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    version INTEGER NOT NULL,
    subject TEXT,
    content TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_notification_templates_version UNIQUE (product_id, template_key, channel, version),
    CONSTRAINT chk_notification_templates_version CHECK (version > 0),
    CONSTRAINT chk_notification_templates_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP')),
    CONSTRAINT chk_notification_templates_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED'))
);

CREATE UNIQUE INDEX uk_notification_templates_active
    ON notification_templates (product_id, template_key, channel)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_notification_templates_product_status
    ON notification_templates (product_id, status);

CREATE TABLE user_notification_preferences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products (id),
    external_user_id VARCHAR(160) NOT NULL,
    category VARCHAR(80) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_user_notification_preferences UNIQUE (product_id, external_user_id, category, channel),
    CONSTRAINT chk_user_notification_preferences_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP'))
);

CREATE INDEX idx_user_notification_preferences_user
    ON user_notification_preferences (product_id, external_user_id);

CREATE TABLE notification_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products (id),
    idempotency_key VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL,
    total_count INTEGER NOT NULL,
    accepted_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_notification_batches_idempotency UNIQUE (product_id, idempotency_key),
    CONSTRAINT chk_notification_batches_status CHECK (
        status IN ('ACCEPTED', 'PROCESSING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED')
    ),
    CONSTRAINT chk_notification_batches_counts CHECK (
        total_count >= 0
        AND accepted_count >= 0
        AND failed_count >= 0
        AND accepted_count + failed_count <= total_count
    )
);

CREATE INDEX idx_notification_batches_product_created
    ON notification_batches (product_id, created_at DESC);

CREATE TABLE notification_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products (id),
    batch_id UUID REFERENCES notification_batches (id),
    template_id UUID NOT NULL REFERENCES notification_templates (id),
    external_user_id VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    category VARCHAR(80) NOT NULL,
    priority VARCHAR(32) NOT NULL,
    payload JSONB NOT NULL,
    recipient JSONB NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_notification_requests_idempotency UNIQUE (product_id, idempotency_key),
    CONSTRAINT chk_notification_requests_priority CHECK (priority IN ('HIGH', 'NORMAL', 'LOW')),
    CONSTRAINT chk_notification_requests_status CHECK (
        status IN ('ACCEPTED', 'DELIVERY_CREATED', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED', 'SKIPPED')
    )
);

CREATE INDEX idx_notification_requests_user_created
    ON notification_requests (product_id, external_user_id, created_at DESC);

CREATE INDEX idx_notification_requests_status_created
    ON notification_requests (status, created_at);

CREATE INDEX idx_notification_requests_batch
    ON notification_requests (batch_id);

CREATE TABLE notification_deliveries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_request_id UUID NOT NULL REFERENCES notification_requests (id),
    channel VARCHAR(32) NOT NULL,
    provider VARCHAR(80),
    destination VARCHAR(320) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    next_attempt_at TIMESTAMPTZ,
    locked_until TIMESTAMPTZ,
    provider_message_id VARCHAR(200),
    last_error_code VARCHAR(120),
    last_error_message TEXT,
    provider_response JSONB,
    sent_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_notification_deliveries_request_channel UNIQUE (notification_request_id, channel),
    CONSTRAINT chk_notification_deliveries_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP')),
    CONSTRAINT chk_notification_deliveries_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'SENT', 'DELIVERED', 'FAILED', 'RETRY_SCHEDULED', 'DLQ', 'SKIPPED')
    ),
    CONSTRAINT chk_notification_deliveries_attempts CHECK (
        attempt_count >= 0
        AND max_attempts > 0
        AND attempt_count <= max_attempts
    )
);

CREATE INDEX idx_notification_deliveries_request
    ON notification_deliveries (notification_request_id);

CREATE INDEX idx_notification_deliveries_status_next_attempt
    ON notification_deliveries (status, next_attempt_at);

CREATE INDEX idx_notification_deliveries_provider_message
    ON notification_deliveries (provider, provider_message_id)
    WHERE provider_message_id IS NOT NULL;

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(32) NOT NULL,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_outbox_events_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT chk_outbox_events_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX idx_outbox_events_status_available
    ON outbox_events (status, available_at, created_at);

CREATE INDEX idx_outbox_events_aggregate
    ON outbox_events (aggregate_type, aggregate_id);
