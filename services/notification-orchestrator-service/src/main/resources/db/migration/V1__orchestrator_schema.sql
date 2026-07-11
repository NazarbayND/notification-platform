CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE processed_events (
    consumer_name VARCHAR(160) NOT NULL,
    event_id VARCHAR(80) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer_name, event_id)
);

CREATE TABLE notification_idempotency (
    tenant_id VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    notification_id VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, idempotency_key)
);

CREATE TABLE template_projection (
    tenant_id VARCHAR(160) NOT NULL,
    template_key VARCHAR(160) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    aggregate_id VARCHAR(160) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    subject TEXT,
    body TEXT NOT NULL,
    required_variables JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, template_key, channel)
);

CREATE TABLE preference_projection (
    tenant_id VARCHAR(160) NOT NULL,
    user_id VARCHAR(160) NOT NULL,
    product_id VARCHAR(160) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    aggregate_id VARCHAR(160) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    allowed BOOLEAN NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, user_id, product_id, channel)
);

CREATE TABLE orchestration_deliveries (
    delivery_id VARCHAR(80) PRIMARY KEY,
    notification_id VARCHAR(80) NOT NULL,
    tenant_id VARCHAR(160) NOT NULL,
    recipient_id VARCHAR(160) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (notification_id, recipient_id, channel)
);

CREATE TABLE orchestration_outbox (
    event_id VARCHAR(80) PRIMARY KEY,
    target_type VARCHAR(20) NOT NULL,
    target_name VARCHAR(200) NOT NULL,
    message_key VARCHAR(400),
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_orchestration_outbox_poll
    ON orchestration_outbox (status, next_attempt_at, created_at);
