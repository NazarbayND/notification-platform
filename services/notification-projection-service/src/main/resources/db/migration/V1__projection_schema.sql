CREATE TABLE notifications (
    notification_id VARCHAR(80) PRIMARY KEY,
    request_id VARCHAR(80),
    tenant_id VARCHAR(160) NOT NULL,
    product_id VARCHAR(160),
    user_id VARCHAR(160),
    template_id VARCHAR(160),
    requested_channels JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(40) NOT NULL,
    reason_code VARCHAR(120),
    reason_message TEXT,
    requested_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE deliveries (
    delivery_id VARCHAR(80) PRIMARY KEY,
    notification_id VARCHAR(80) NOT NULL REFERENCES notifications(notification_id),
    tenant_id VARCHAR(160) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    status VARCHAR(40) NOT NULL,
    attempt INTEGER NOT NULL,
    provider_message_id VARCHAR(240),
    error_code VARCHAR(120),
    error_message TEXT,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE delivery_attempts (
    event_id VARCHAR(80) PRIMARY KEY,
    delivery_id VARCHAR(80) NOT NULL,
    notification_id VARCHAR(80) NOT NULL,
    attempt INTEGER NOT NULL,
    status VARCHAR(40) NOT NULL,
    provider_message_id VARCHAR(240),
    error_code VARCHAR(120),
    error_message TEXT,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE processed_events (
    consumer_name VARCHAR(160) NOT NULL,
    event_id VARCHAR(80) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer_name, event_id)
);

CREATE INDEX idx_projection_tenant_time ON notifications (tenant_id, requested_at DESC);
CREATE INDEX idx_projection_user_time ON notifications (tenant_id, user_id, requested_at DESC);
CREATE INDEX idx_projection_product_time ON notifications (product_id, requested_at DESC);
CREATE INDEX idx_projection_delivery_notification ON deliveries (notification_id, updated_at DESC);
