CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    product_id VARCHAR(160) NOT NULL,
    user_id VARCHAR(160) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    template_key VARCHAR(160) NOT NULL,
    priority VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    destination VARCHAR(512) NOT NULL,
    variables JSONB NOT NULL DEFAULT '{}'::jsonb,
    correlation_id VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_notifications_idempotency UNIQUE (product_id, idempotency_key),
    CONSTRAINT chk_notifications_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP', 'WEBHOOK')),
    CONSTRAINT chk_notifications_priority CHECK (priority IN ('HIGH', 'NORMAL', 'LOW')),
    CONSTRAINT chk_notifications_status CHECK (status IN ('ACCEPTED', 'SKIPPED', 'SENT', 'FAILED', 'PARTIAL_FAILED'))
);

CREATE INDEX idx_notifications_status_created
    ON notifications (status, created_at DESC);

CREATE INDEX idx_notifications_user_created
    ON notifications (product_id, user_id, created_at DESC);

CREATE TABLE notification_deliveries (
    id UUID PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES notifications (id),
    channel VARCHAR(32) NOT NULL,
    destination VARCHAR(512) NOT NULL,
    subject TEXT NOT NULL,
    body TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 5,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_notification_delivery_channel UNIQUE (notification_id, channel),
    CONSTRAINT chk_notification_delivery_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP', 'WEBHOOK')),
    CONSTRAINT chk_notification_delivery_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'DEAD_LETTER'))
);

CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    aggregate_type VARCHAR(120) NOT NULL,
    aggregate_id VARCHAR(160) NOT NULL,
    event_type VARCHAR(160) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 10,
    locked_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error TEXT,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED', 'DEAD_LETTER'))
);

CREATE INDEX idx_outbox_poll
    ON outbox_events (status, next_attempt_at, created_at);

CREATE INDEX idx_outbox_aggregate
    ON outbox_events (aggregate_type, aggregate_id);
