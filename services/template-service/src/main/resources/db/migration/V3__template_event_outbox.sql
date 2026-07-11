ALTER TABLE notification_templates ADD COLUMN aggregate_version BIGINT NOT NULL DEFAULT 1;

CREATE TABLE domain_event_outbox (
    event_id VARCHAR(80) PRIMARY KEY,
    topic VARCHAR(200) NOT NULL,
    message_key VARCHAR(200) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_template_event_outbox_poll ON domain_event_outbox(status,next_attempt_at,created_at);

INSERT INTO domain_event_outbox(event_id,topic,message_key,payload,created_at)
SELECT gen_random_uuid()::text,'template.events.v1',id::text,
       jsonb_build_object(
         'eventId',gen_random_uuid()::text,'eventType','TemplateCreated','aggregateId',id::text,
         'aggregateVersion',aggregate_version,'occurredAt',updated_at,'schemaVersion',1,
         'payload',jsonb_build_object('productId',product_id,'templateKey',template_key,'channel',channel,
           'subject',subject,'body',body,'requiredVariables',required_variables,'status',status)),
       updated_at
FROM notification_templates;
