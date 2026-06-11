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
    CONSTRAINT uk_email_attempt_event UNIQUE (event_id)
);

CREATE TABLE test_email_messages (
    id UUID PRIMARY KEY,
    recipient VARCHAR(512) NOT NULL,
    subject TEXT NOT NULL,
    body TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(120),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL
);
