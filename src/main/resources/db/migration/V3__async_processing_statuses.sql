ALTER TABLE notification_deliveries
    DROP CONSTRAINT IF EXISTS chk_notification_deliveries_status;

ALTER TABLE notification_deliveries
    ADD CONSTRAINT chk_notification_deliveries_status CHECK (
        status IN (
            'PENDING',
            'PROCESSING',
            'SENDING',
            'SENT',
            'DELIVERED',
            'FAILED',
            'RETRY_SCHEDULED',
            'DLQ',
            'DEAD_LETTERED',
            'SKIPPED'
        )
    );
