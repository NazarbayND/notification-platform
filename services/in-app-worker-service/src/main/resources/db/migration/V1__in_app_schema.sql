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
    CONSTRAINT uk_in_app_attempt_event UNIQUE (event_id)
);

CREATE TABLE in_app_notifications (
    id UUID PRIMARY KEY,
    user_id VARCHAR(160) NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    read BOOLEAN NOT NULL DEFAULT false,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(120),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ
);

CREATE INDEX idx_in_app_user_read_created
    ON in_app_notifications (user_id, read, created_at DESC);
