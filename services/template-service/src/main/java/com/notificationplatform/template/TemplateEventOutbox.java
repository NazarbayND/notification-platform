package com.notificationplatform.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notificationplatform.contracts.AggregateChangedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class TemplateEventOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final KafkaTemplate<Object,Object> kafka;

    TemplateEventOutbox(JdbcTemplate jdbc, ObjectMapper mapper, KafkaTemplate<Object,Object> kafka) {
        this.jdbc = jdbc; this.mapper = mapper; this.kafka = kafka;
    }

    void append(String eventType, UUID id, long version, TemplateServiceApplication.Template template) {
        Map<String,Object> payload = Map.of(
                "productId", template.productId(), "templateKey", template.key(), "channel", template.channel(),
                "subject", template.subject(), "body", template.body(), "requiredVariables", template.requiredVariables(),
                "status", template.status());
        append(new AggregateChangedEvent(UUID.randomUUID().toString(), eventType, id.toString(), version,
                Instant.now(), 1, payload));
    }

    void appendDeleted(UUID id, long version, TemplateServiceApplication.Template template) {
        append("TemplateDeleted", id, version, template);
    }

    private void append(AggregateChangedEvent event) {
        try {
            jdbc.update("INSERT INTO domain_event_outbox(event_id,topic,message_key,payload,created_at) VALUES (?,'template.events.v1',?,?::jsonb,?)",
                    event.eventId(), event.aggregateId(), mapper.writeValueAsString(event), java.sql.Timestamp.from(event.occurredAt()));
        } catch (Exception e) { throw new IllegalArgumentException("Could not append template event", e); }
    }

    @Scheduled(fixedDelayString = "${domain-events.fixed-delay-ms:500}")
    void publish() {
        List<Row> rows = jdbc.query("""
                WITH s AS (SELECT event_id FROM domain_event_outbox WHERE status IN ('PENDING','FAILED','PROCESSING') AND next_attempt_at<=now()
                  ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED)
                UPDATE domain_event_outbox o SET status='PROCESSING',attempt_count=attempt_count+1,next_attempt_at=now()+interval '1 minute'
                FROM s WHERE o.event_id=s.event_id
                RETURNING o.event_id,o.topic,o.message_key,o.payload::text,o.attempt_count
                """, (rs,n) -> new Row(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5)));
        for (Row row : rows) {
            try {
                JsonNode payload = mapper.readTree(row.payload());
                kafka.send(row.topic(), row.key(), payload).get(5, TimeUnit.SECONDS);
                jdbc.update("UPDATE domain_event_outbox SET status='PUBLISHED',published_at=now(),last_error=NULL WHERE event_id=?",row.id());
            } catch (Exception e) {
                jdbc.update("UPDATE domain_event_outbox SET status=?,last_error=?,next_attempt_at=now()+interval '30 seconds' WHERE event_id=?",
                        row.attempt()>=10?"DEAD_LETTER":"FAILED", String.valueOf(e.getMessage()), row.id());
            }
        }
    }
    record Row(String id,String topic,String key,String payload,int attempt) {}
}
