DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = current_schema()
          AND indexname = 'idx_outbox_events_pending_available'
    ) AND NOT EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = current_schema()
          AND indexname = 'idx_outbox_pending_ready'
    ) THEN
        ALTER INDEX idx_outbox_events_pending_available RENAME TO idx_outbox_pending_ready;
    ELSIF NOT EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = current_schema()
          AND indexname = 'idx_outbox_pending_ready'
    ) THEN
        CREATE INDEX idx_outbox_pending_ready
            ON outbox_events (available_at, created_at)
            WHERE status = 'PENDING';
    END IF;
END $$;
