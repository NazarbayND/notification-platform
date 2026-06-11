package com.notificationplatform.outboxpublisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@EnableScheduling
@SpringBootApplication
public class OutboxPublisherServiceApplication {

    static final String DELIVERY_EXCHANGE = "notification.delivery";

    public static void main(String[] args) {
        SpringApplication.run(OutboxPublisherServiceApplication.class, args);
    }

    private static java.sql.Timestamp ts(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }

    @Bean
    Jackson2JsonMessageConverter jackson2JsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    DirectExchange deliveryExchange() {
        return new DirectExchange(DELIVERY_EXCHANGE, true, false);
    }

    @Bean Queue emailQueue() { return new Queue("delivery.email", true); }
    @Bean Queue smsQueue() { return new Queue("delivery.sms", true); }
    @Bean Queue pushQueue() { return new Queue("delivery.push", true); }
    @Bean Queue inAppQueue() { return new Queue("delivery.in-app", true); }
    @Bean Queue webhookQueue() { return new Queue("delivery.webhook", true); }

    @Bean Binding emailBinding(Queue emailQueue, DirectExchange deliveryExchange) {
        return BindingBuilder.bind(emailQueue).to(deliveryExchange).with("EMAIL");
    }

    @Bean Binding smsBinding(Queue smsQueue, DirectExchange deliveryExchange) {
        return BindingBuilder.bind(smsQueue).to(deliveryExchange).with("SMS");
    }

    @Bean Binding pushBinding(Queue pushQueue, DirectExchange deliveryExchange) {
        return BindingBuilder.bind(pushQueue).to(deliveryExchange).with("PUSH");
    }

    @Bean Binding inAppBinding(Queue inAppQueue, DirectExchange deliveryExchange) {
        return BindingBuilder.bind(inAppQueue).to(deliveryExchange).with("IN_APP");
    }

    @Bean Binding webhookBinding(Queue webhookQueue, DirectExchange deliveryExchange) {
        return BindingBuilder.bind(webhookQueue).to(deliveryExchange).with("WEBHOOK");
    }

    @RestController
    static class HealthController {
        @GetMapping({"/health/live", "/health/ready"})
        Health health() {
            return new Health("UP", Instant.now());
        }
    }

    @RestController
    @RequestMapping("/outbox/events")
    static class OutboxController {
        private final OutboxRepository repository;
        private final OutboxPublisher publisher;

        OutboxController(OutboxRepository repository, OutboxPublisher publisher) {
            this.repository = repository;
            this.publisher = publisher;
        }

        @GetMapping
        List<OutboxEvent> list() {
            return repository.findRecent();
        }

        @PostMapping("/poll")
        PublishResult pollOnce() {
            return publisher.publishBatch();
        }

        @PostMapping("/{eventId}/retry")
        OutboxEvent retry(@PathVariable UUID eventId) {
            return repository.retry(eventId);
        }
    }

    @Service
    static class OutboxPublisher {
        private final OutboxRepository repository;
        private final RabbitTemplate rabbitTemplate;
        private final int batchSize;

        OutboxPublisher(
                OutboxRepository repository,
                RabbitTemplate rabbitTemplate,
                @Value("${OUTBOX_BATCH_SIZE:${outbox.batch-size:100}}") int batchSize) {
            this.repository = repository;
            this.rabbitTemplate = rabbitTemplate;
            this.batchSize = batchSize;
        }

        @Scheduled(fixedDelayString = "${OUTBOX_FIXED_DELAY_MS:${outbox.fixed-delay-ms:500}}")
        void scheduledPublish() {
            publishBatch();
        }

        PublishResult publishBatch() {
            List<OutboxEvent> events = repository.claim(batchSize, Duration.ofMinutes(5));
            int published = 0;
            int failed = 0;
            for (OutboxEvent event : events) {
                try {
                    DeliveryJob job = DeliveryJob.from(event);
                    rabbitTemplate.convertAndSend(DELIVERY_EXCHANGE, job.channel(), job);
                    repository.markPublished(event.eventId());
                    published++;
                } catch (RuntimeException exception) {
                    repository.markFailed(event, exception.getMessage());
                    failed++;
                }
            }
            return new PublishResult(events.size(), published, failed);
        }
    }

    @Repository
    static class OutboxRepository {
        private final JdbcTemplate jdbc;
        private final ObjectMapper objectMapper;

        OutboxRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
            this.jdbc = jdbc;
            this.objectMapper = objectMapper;
        }

        List<OutboxEvent> findRecent() {
            return jdbc.query("""
                    SELECT event_id, aggregate_type, aggregate_id, event_type, payload, status, attempt_count,
                           max_attempts, locked_until, next_attempt_at, last_error, published_at, created_at, updated_at
                    FROM outbox_events
                    ORDER BY created_at DESC
                    LIMIT 100
                    """, this::map);
        }

        @Transactional
        List<OutboxEvent> claim(int batchSize, Duration lockDuration) {
            Instant now = Instant.now();
            Instant lockedUntil = now.plus(lockDuration);
            return jdbc.query("""
                    WITH candidates AS (
                        SELECT event_id
                        FROM outbox_events
                        WHERE status IN ('PENDING', 'FAILED')
                          AND next_attempt_at <= ?
                          AND (locked_until IS NULL OR locked_until < ?)
                        ORDER BY created_at
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                    )
                    UPDATE outbox_events e
                    SET status = 'PROCESSING',
                        locked_until = ?,
                        attempt_count = e.attempt_count + 1,
                        updated_at = ?
                    FROM candidates
                    WHERE e.event_id = candidates.event_id
                    RETURNING e.event_id, e.aggregate_type, e.aggregate_id, e.event_type, e.payload, e.status,
                              e.attempt_count, e.max_attempts, e.locked_until, e.next_attempt_at, e.last_error,
                              e.published_at, e.created_at, e.updated_at
                    """, this::map, ts(now), ts(now), batchSize, ts(lockedUntil), ts(now));
        }

        @Transactional
        void markPublished(UUID eventId) {
            jdbc.update("""
                    UPDATE outbox_events
                    SET status = 'PUBLISHED', published_at = ?, locked_until = NULL, last_error = NULL, updated_at = ?
                    WHERE event_id = ?
                    """, ts(Instant.now()), ts(Instant.now()), eventId);
        }

        @Transactional
        void markFailed(OutboxEvent event, String error) {
            String status = event.attemptCount() >= event.maxAttempts() ? "DEAD_LETTER" : "FAILED";
            Instant nextAttemptAt = "DEAD_LETTER".equals(status)
                    ? Instant.now()
                    : Instant.now().plus(backoff(event.attemptCount()));
            jdbc.update("""
                    UPDATE outbox_events
                    SET status = ?, locked_until = NULL, next_attempt_at = ?, last_error = ?, updated_at = ?
                    WHERE event_id = ?
                    """, status, ts(nextAttemptAt), truncate(error), ts(Instant.now()), event.eventId());
        }

        @Transactional
        OutboxEvent retry(UUID eventId) {
            int updated = jdbc.update("""
                    UPDATE outbox_events
                    SET status = 'PENDING', locked_until = NULL, next_attempt_at = ?, last_error = NULL, updated_at = ?
                    WHERE event_id = ? AND status IN ('FAILED', 'DEAD_LETTER')
                    """, ts(Instant.now()), ts(Instant.now()), eventId);
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Event cannot be retried: " + eventId);
            }
            return jdbc.queryForObject("""
                    SELECT event_id, aggregate_type, aggregate_id, event_type, payload, status, attempt_count,
                           max_attempts, locked_until, next_attempt_at, last_error, published_at, created_at, updated_at
                    FROM outbox_events
                    WHERE event_id = ?
                    """, this::map, eventId);
        }

        private Duration backoff(int attemptCount) {
            long seconds = Math.min(900, (long) Math.pow(2, Math.max(1, attemptCount)) * 5);
            return Duration.ofSeconds(seconds);
        }

        private String truncate(String error) {
            if (error == null) {
                return null;
            }
            return error.length() > 1_000 ? error.substring(0, 1_000) : error;
        }

        private OutboxEvent map(ResultSet rs, int rowNum) throws SQLException {
            return new OutboxEvent(
                    rs.getObject("event_id", UUID.class),
                    rs.getString("aggregate_type"),
                    rs.getString("aggregate_id"),
                    rs.getString("event_type"),
                    readMap(rs.getString("payload")),
                    rs.getString("status"),
                    rs.getInt("attempt_count"),
                    rs.getInt("max_attempts"),
                    rs.getTimestamp("locked_until") == null ? null : rs.getTimestamp("locked_until").toInstant(),
                    rs.getTimestamp("next_attempt_at") == null ? null : rs.getTimestamp("next_attempt_at").toInstant(),
                    rs.getString("last_error"),
                    rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        }

        private Map<String, Object> readMap(String json) {
            try {
                return objectMapper.readValue(json, Map.class);
            } catch (Exception exception) {
                throw new IllegalStateException("Could not parse outbox payload", exception);
            }
        }
    }

    record Health(String status, Instant checkedAt) {
    }

    record PublishResult(int fetched, int published, int failed) {
    }

    record OutboxEvent(
            UUID eventId,
            String aggregateType,
            String aggregateId,
            String eventType,
            Map<String, Object> payload,
            String status,
            int attemptCount,
            int maxAttempts,
            Instant lockedUntil,
            Instant nextAttemptAt,
            String lastError,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    record DeliveryJob(
            UUID eventId,
            UUID notificationId,
            UUID deliveryId,
            String channel,
            String destination,
            String subject,
            String body,
            String priority,
            String correlationId) {
        static DeliveryJob from(OutboxEvent event) {
            Map<String, Object> payload = event.payload();
            return new DeliveryJob(
                    event.eventId(),
                    UUID.fromString(String.valueOf(payload.get("notificationId"))),
                    UUID.fromString(String.valueOf(payload.get("deliveryId"))),
                    String.valueOf(payload.get("channel")),
                    String.valueOf(payload.get("destination")),
                    String.valueOf(payload.get("subject")),
                    String.valueOf(payload.get("body")),
                    String.valueOf(payload.getOrDefault("priority", "NORMAL")),
                    String.valueOf(payload.getOrDefault("correlationId", "")));
        }
    }
}
