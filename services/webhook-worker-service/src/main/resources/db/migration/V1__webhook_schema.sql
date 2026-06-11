CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE delivery_attempts (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    notification_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    channel VARCHAR(32) NOT NULL,
    destination VARCHAR(512) NOT NULL,
    provider VARCHAR(80) NOT NULL,
    provider_message_id VARCHAR(200),
    status VARCHAR(32) NOT NULL,
    raw_response JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_code VARCHAR(120),
    error_message TEXT,
    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_webhook_attempt_event UNIQUE (event_id)
);

CREATE TABLE received_webhooks (
    id UUID PRIMARY KEY,
    method VARCHAR(16) NOT NULL,
    path TEXT NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    body TEXT,
    created_at TIMESTAMPTZ NOT NULL
);
