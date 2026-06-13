CREATE TABLE notification_products (
    id VARCHAR(160) PRIMARY KEY,
    name VARCHAR(240) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_products_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE INDEX idx_products_status_updated
    ON notification_products (status, updated_at DESC);

INSERT INTO notification_products (id, name, status, created_at, updated_at)
SELECT product_id, product_id, 'ACTIVE', MIN(created_at), MAX(updated_at)
FROM notification_templates
GROUP BY product_id
ON CONFLICT (id) DO NOTHING;
