ALTER TABLE notification_requests
    ADD COLUMN template_key VARCHAR(120),
    ADD COLUMN requested_channels JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN expires_at TIMESTAMPTZ;

UPDATE notification_requests request
SET template_key = template.template_key,
    requested_channels = jsonb_build_array(template.channel)
FROM notification_templates template
WHERE request.template_id = template.id;

ALTER TABLE notification_requests
    ALTER COLUMN template_key SET NOT NULL,
    ADD CONSTRAINT chk_notification_requests_requested_channels_array
        CHECK (jsonb_typeof(requested_channels) = 'array');

ALTER TABLE notification_deliveries
    ADD COLUMN template_id UUID REFERENCES notification_templates (id),
    ADD COLUMN expires_at TIMESTAMPTZ;

UPDATE notification_deliveries delivery
SET template_id = request.template_id,
    expires_at = request.expires_at
FROM notification_requests request
WHERE delivery.notification_request_id = request.id;

ALTER TABLE notification_deliveries
    ALTER COLUMN template_id SET NOT NULL;

ALTER TABLE notification_requests
    DROP CONSTRAINT notification_requests_template_id_fkey,
    DROP COLUMN template_id;

CREATE TABLE delivery_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_delivery_id UUID NOT NULL REFERENCES notification_deliveries (id),
    attempt_number INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    provider VARCHAR(80),
    provider_message_id VARCHAR(200),
    error_code VARCHAR(120),
    error_message TEXT,
    request_payload JSONB,
    response_payload JSONB,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_delivery_attempts_delivery_attempt_number UNIQUE (notification_delivery_id, attempt_number),
    CONSTRAINT chk_delivery_attempts_attempt_number CHECK (attempt_number > 0),
    CONSTRAINT chk_delivery_attempts_status CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX idx_delivery_attempts_delivery_created
    ON delivery_attempts (notification_delivery_id, created_at);

DROP INDEX idx_notification_deliveries_status_next_attempt;

CREATE INDEX idx_notification_deliveries_ready_for_attempt
    ON notification_deliveries (next_attempt_at, expires_at, created_at)
    WHERE status IN ('PENDING', 'RETRY_SCHEDULED');

DROP INDEX idx_outbox_events_status_available;

CREATE INDEX idx_outbox_events_pending_available
    ON outbox_events (available_at, created_at)
    WHERE status = 'PENDING';
