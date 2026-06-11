CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE user_notification_preferences (
    id UUID PRIMARY KEY,
    user_id VARCHAR(160) NOT NULL,
    product_id VARCHAR(160) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    allowed BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_user_product_channel UNIQUE (user_id, product_id, channel),
    CONSTRAINT chk_preference_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP', 'WEBHOOK'))
);

CREATE INDEX idx_preferences_product_user
    ON user_notification_preferences (product_id, user_id);
