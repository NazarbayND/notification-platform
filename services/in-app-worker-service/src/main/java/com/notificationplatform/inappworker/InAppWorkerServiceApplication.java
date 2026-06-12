package com.notificationplatform.inappworker;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.MDC;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class InAppWorkerServiceApplication {

    static final String DELIVERY_EXCHANGE = "notification.delivery";
    static final String IN_APP_QUEUE = "delivery.in-app";

    public static void main(String[] args) {
        SpringApplication.run(InAppWorkerServiceApplication.class, args);
    }

    private static java.sql.Timestamp ts(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }

    @Bean
    Jackson2JsonMessageConverter jackson2JsonMessageConverter(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    DirectExchange deliveryExchange() {
        return new DirectExchange(DELIVERY_EXCHANGE, true, false);
    }

    @Bean
    Queue inAppQueue() {
        return new Queue(IN_APP_QUEUE, true);
    }

    @Bean
    Binding inAppBinding(Queue inAppQueue, DirectExchange deliveryExchange) {
        return BindingBuilder.bind(inAppQueue).to(deliveryExchange).with("IN_APP");
    }

    @RestController
    static class HealthController {
        @GetMapping({"/health/live", "/health/ready"})
        Health health() {
            return new Health("UP", Instant.now());
        }
    }

    @RestController
    @RequestMapping("/worker")
    static class WorkerController {
        private final InAppProvider provider;

        WorkerController(InAppProvider provider) {
            this.provider = provider;
        }

        @GetMapping("/status")
        WorkerStatus status() {
            return new WorkerStatus("IN_APP", "READY", "Idempotency key: eventId or notificationId + channel");
        }

        @PostMapping("/test-send")
        ProviderResult testSend(@Valid @RequestBody CreateInAppNotificationCommand command) {
            return provider.createInAppNotification(command);
        }
    }

    @RestController
    @RequestMapping("/users/{userId}/in-app-notifications")
    static class UserInAppController {
        private final DbInAppProvider provider;

        UserInAppController(DbInAppProvider provider) {
            this.provider = provider;
        }

        @GetMapping
        List<InAppNotification> list(@PathVariable String userId) {
            return provider.forUser(userId);
        }

        @PostMapping("/{id}/read")
        InAppNotification markRead(@PathVariable String userId, @PathVariable UUID id) {
            return provider.markRead(userId, id);
        }
    }

    @RestController
    @RequestMapping("/test/in-app-notifications")
    static class TestInAppController {
        private final DbInAppProvider provider;

        TestInAppController(DbInAppProvider provider) {
            this.provider = provider;
        }

        @GetMapping
        List<InAppNotification> list() {
            return provider.all();
        }

        @DeleteMapping
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void clear() {
            provider.clear();
        }
    }

    @org.springframework.stereotype.Component
    static class InAppDeliveryConsumer {
        private final DbInAppProvider provider;
        private final MeterRegistry meterRegistry;

        InAppDeliveryConsumer(DbInAppProvider provider, MeterRegistry meterRegistry) {
            this.provider = provider;
            this.meterRegistry = meterRegistry;
        }

        @RabbitListener(queues = IN_APP_QUEUE)
        void consume(DeliveryJob job, @Header(name = "X-Correlation-Id", required = false) String correlationId) {
            Timer.Sample processingTimer = Timer.start(meterRegistry);
            meterRegistry.counter("worker_messages_consumed_total", "service", "in-app-worker-service", "channel", "IN_APP").increment();
            putContext(job, correlationId);
            try {
                ProviderResult result = provider.createFromJob(job);
                if (result == null) {
                    meterRegistry.counter("worker_duplicate_events_skipped_total", "service", "in-app-worker-service", "channel", "IN_APP").increment();
                    return;
                }
                meterRegistry.counter("worker_messages_processed_total", "service", "in-app-worker-service", "channel", "IN_APP", "status", result.status()).increment();
                meterRegistry.counter("delivery_attempt_total", "channel", "IN_APP", "provider", result.provider(), "status", result.status()).increment();
                if (!"SENT".equals(result.status())) {
                    meterRegistry.counter("provider_error_total", "channel", "IN_APP", "provider", result.provider(), "error_code", String.valueOf(result.errorCode())).increment();
                }
            } catch (RuntimeException exception) {
                meterRegistry.counter("worker_messages_failed_total", "service", "in-app-worker-service", "channel", "IN_APP", "reason", exception.getClass().getSimpleName()).increment();
                throw exception;
            } finally {
                processingTimer.stop(Timer.builder("worker_processing_duration_seconds")
                        .tag("service", "in-app-worker-service")
                        .tag("channel", "IN_APP")
                        .register(meterRegistry));
                MDC.clear();
            }
        }

        private void putContext(DeliveryJob job, String correlationId) {
            if (correlationId != null && !correlationId.isBlank()) {
                MDC.put("correlationId", correlationId);
            } else if (job.correlationId() != null && !job.correlationId().isBlank()) {
                MDC.put("correlationId", job.correlationId());
            }
            MDC.put("eventId", String.valueOf(job.eventId()));
            MDC.put("notificationId", String.valueOf(job.notificationId()));
            MDC.put("channel", "IN_APP");
        }
    }

    @Bean
    DbInAppProvider dbInAppProvider(
            JdbcTemplate jdbc,
            @Value("${TEST_PROVIDER_FAILURE_RATE:0.0}") double failureRate,
            @Value("${TEST_PROVIDER_LATENCY_MS:0}") long latencyMs) {
        return new DbInAppProvider(jdbc, failureRate, latencyMs);
    }

    @Bean
    @Primary
    InAppProvider inAppProvider(DbInAppProvider provider) {
        return provider;
    }

    record Health(String status, Instant checkedAt) {
    }

    record WorkerStatus(String channel, String status, String idempotencyRule) {
    }

    interface InAppProvider {
        ProviderResult createInAppNotification(CreateInAppNotificationCommand command);
    }

    record CreateInAppNotificationCommand(
            @NotBlank String userId,
            @NotBlank String title,
            @NotBlank String body,
            String eventId,
            String notificationId) {
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
    }

    record ProviderResult(
            String provider,
            String providerMessageId,
            String status,
            String rawResponse,
            String errorCode,
            String errorMessage,
            Instant sentAt) {
    }

    record InAppNotification(
            UUID id,
            String userId,
            String title,
            String body,
            boolean read,
            ProviderResult result,
            Instant createdAt,
            Instant readAt) {
    }

    static class DbInAppProvider implements InAppProvider {
        private final ConcurrentMap<UUID, InAppNotification> notifications = new ConcurrentHashMap<>();
        private final JdbcTemplate jdbc;
        private final double failureRate;
        private final long latencyMs;

        DbInAppProvider(JdbcTemplate jdbc, double failureRate, long latencyMs) {
            this.jdbc = jdbc;
            this.failureRate = failureRate;
            this.latencyMs = latencyMs;
        }

        DbInAppProvider(double failureRate, long latencyMs) {
            this(null, failureRate, latencyMs);
        }

        @Transactional
        ProviderResult createFromJob(DeliveryJob job) {
            if (!markProcessing(job.eventId())) {
                return null;
            }
            ProviderResult result = createInAppNotification(new CreateInAppNotificationCommand(
                    job.destination(), job.subject(), job.body(), job.eventId().toString(), job.notificationId().toString()));
            if (jdbc != null) {
                jdbc.update("""
                        INSERT INTO delivery_attempts (
                            id, event_id, notification_id, delivery_id, channel, destination, provider,
                            provider_message_id, status, raw_response, error_code, error_message, sent_at, created_at
                        )
                        VALUES (?, ?, ?, ?, 'IN_APP', ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                        """,
                        UUID.randomUUID(), job.eventId(), job.notificationId(), job.deliveryId(), job.destination(),
                        result.provider(), result.providerMessageId(), result.status(), "{\"stored\":true}",
                        result.errorCode(), result.errorMessage(), ts(result.sentAt()), ts(Instant.now()));
            }
            return result;
        }

        private boolean markProcessing(UUID eventId) {
            if (jdbc == null) {
                return true;
            }
            try {
                jdbc.update("INSERT INTO processed_events (event_id, processed_at) VALUES (?, ?)", eventId, ts(Instant.now()));
                return true;
            } catch (DuplicateKeyException duplicate) {
                return false;
            }
        }

        @Override
        public ProviderResult createInAppNotification(CreateInAppNotificationCommand command) {
            simulateLatency();
            ProviderResult result = simulate(command.userId());
            UUID id = UUID.randomUUID();
            InAppNotification notification = new InAppNotification(
                    id,
                    command.userId(),
                    command.title(),
                    command.body(),
                    false,
                    result,
                    Instant.now(),
                    null);
            if (jdbc != null) {
                jdbc.update("""
                        INSERT INTO in_app_notifications (id, user_id, title, body, read, status, error_code, error_message, created_at, read_at)
                        VALUES (?, ?, ?, ?, false, ?, ?, ?, ?, NULL)
                        """, notification.id(), notification.userId(), notification.title(), notification.body(),
                        result.status(), result.errorCode(), result.errorMessage(), ts(notification.createdAt()));
            } else {
                notifications.put(id, notification);
            }
            return result;
        }

        List<InAppNotification> all() {
            if (jdbc != null) {
                return jdbc.query("""
                        SELECT id, user_id, title, body, read, status, error_code, error_message, created_at, read_at
                        FROM in_app_notifications
                        ORDER BY created_at DESC
                        """, this::map);
            }
            return notifications.values().stream().toList();
        }

        List<InAppNotification> forUser(String userId) {
            if (jdbc != null) {
                return jdbc.query("""
                        SELECT id, user_id, title, body, read, status, error_code, error_message, created_at, read_at
                        FROM in_app_notifications
                        WHERE user_id = ?
                        ORDER BY created_at DESC
                        """, this::map, userId);
            }
            return notifications.values().stream()
                    .filter(notification -> notification.userId().equals(userId))
                    .toList();
        }

        InAppNotification markRead(String userId, UUID id) {
            if (jdbc != null) {
                int updated = jdbc.update("""
                        UPDATE in_app_notifications
                        SET read = true, read_at = ?
                        WHERE id = ? AND user_id = ?
                        """, ts(Instant.now()), id, userId);
                if (updated == 0) {
                    return null;
                }
                return jdbc.queryForObject("""
                        SELECT id, user_id, title, body, read, status, error_code, error_message, created_at, read_at
                        FROM in_app_notifications
                        WHERE id = ?
                        """, this::map, id);
            }
            InAppNotification notification = notifications.get(id);
            if (notification == null || !notification.userId().equals(userId)) {
                return null;
            }
            InAppNotification updated = new InAppNotification(
                    notification.id(),
                    notification.userId(),
                    notification.title(),
                    notification.body(),
                    true,
                    notification.result(),
                    notification.createdAt(),
                    Instant.now());
            notifications.put(id, updated);
            return updated;
        }

        void clear() {
            if (jdbc != null) {
                jdbc.update("DELETE FROM in_app_notifications");
            } else {
                notifications.clear();
            }
        }

        private InAppNotification map(ResultSet rs, int rowNum) throws SQLException {
            ProviderResult result = new ProviderResult(
                    "db-in-app",
                    rs.getObject("id", UUID.class).toString(),
                    rs.getString("status"),
                    "stored",
                    rs.getString("error_code"),
                    rs.getString("error_message"),
                    rs.getTimestamp("created_at").toInstant());
            return new InAppNotification(
                    rs.getObject("id", UUID.class),
                    rs.getString("user_id"),
                    rs.getString("title"),
                    rs.getString("body"),
                    rs.getBoolean("read"),
                    result,
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant());
        }

        private ProviderResult simulate(String recipient) {
            String normalized = recipient.toLowerCase(Locale.ROOT);
            if (normalized.contains("timeout")) {
                return failed("TIMEOUT", "Simulated provider timeout");
            }
            if (normalized.contains("rate-limit")) {
                return failed("RATE_LIMIT", "Simulated rate limit");
            }
            if (normalized.contains("fail") || Math.random() < failureRate) {
                return failed("PROVIDER_FAILURE", "Simulated provider failure");
            }
            return new ProviderResult("db-in-app", UUID.randomUUID().toString(), "SENT", "stored", null, null, Instant.now());
        }

        private ProviderResult failed(String code, String message) {
            return new ProviderResult("db-in-app", null, "FAILED", "rejected", code, message, Instant.now());
        }

        private void simulateLatency() {
            if (latencyMs <= 0) {
                return;
            }
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
