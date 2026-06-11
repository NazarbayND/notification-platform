CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE notification_templates (
    id UUID PRIMARY KEY,
    product_id VARCHAR(160) NOT NULL,
    template_key VARCHAR(160) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    subject TEXT NOT NULL,
    body TEXT NOT NULL,
    required_variables JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_templates_active UNIQUE (product_id, template_key, channel, status),
    CONSTRAINT chk_templates_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP', 'WEBHOOK')),
    CONSTRAINT chk_templates_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED'))
);

CREATE INDEX idx_templates_product_channel
    ON notification_templates (product_id, channel, updated_at DESC);
