CREATE INDEX idx_notification_deliveries_sending_lock_recovery
    ON notification_deliveries (locked_until, expires_at, created_at)
    WHERE status = 'SENDING';
