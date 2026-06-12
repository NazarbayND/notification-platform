package com.notificationplatform.pushworker;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.stereotype.Repository;
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
public class PushWorkerServiceApplication {

    static final String DELIVERY_EXCHANGE = "notification.delivery";
    static final String PUSH_QUEUE = "delivery.push";

    public static void main(String[] args) {
        SpringApplication.run(PushWorkerServiceApplication.class, args);
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

    @Bean
    Queue pushQueue() {
        return new Queue(PUSH_QUEUE, true);
    }

    @Bean
    Binding pushBinding(Queue pushQueue, DirectExchange deliveryExchange) {
        return BindingBuilder.bind(pushQueue).to(deliveryExchange).with("PUSH");
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
        private final PushProvider pushProvider;

        WorkerController(PushProvider pushProvider) {
            this.pushProvider = pushProvider;
        }

        @GetMapping("/status")
        WorkerStatus status() {
            return new WorkerStatus("PUSH", "READY", "Idempotency key: eventId");
        }

        @PostMapping("/test-send")
        ProviderResult testSend(@Valid @RequestBody SendPushCommand command) {
            return pushProvider.sendPush(command);
        }
    }

    @RestController
    @RequestMapping("/test/push-messages")
    static class TestPushController {
        private final TestPushProvider provider;

        TestPushController(TestPushProvider provider) {
            this.provider = provider;
        }

        @GetMapping
        List<TestPushMessage> list() {
            return provider.messages();
        }

        @GetMapping("/{id}")
        TestPushMessage get(@PathVariable UUID id) {
            return provider.get(id);
        }

        @DeleteMapping
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void clear() {
            provider.clear();
        }
    }

    @org.springframework.stereotype.Component
    static class PushDeliveryConsumer {
        private final DeliveryRepository repository;
        private final PushProvider provider;
        private final MeterRegistry meterRegistry;

        PushDeliveryConsumer(DeliveryRepository repository, PushProvider provider, MeterRegistry meterRegistry) {
            this.repository = repository;
            this.provider = provider;
            this.meterRegistry = meterRegistry;
        }

        @RabbitListener(queues = PUSH_QUEUE)
        void consume(DeliveryJob job, @Header(name = "X-Correlation-Id", required = false) String correlationId) {
            Timer.Sample processingTimer = Timer.start(meterRegistry);
            meterRegistry.counter("worker_messages_consumed_total", "service", "push-worker-service", "channel", "PUSH").increment();
            putContext(job, correlationId);
            try {
                if (!repository.markProcessing(job.eventId())) {
                    meterRegistry.counter("worker_duplicate_events_skipped_total", "service", "push-worker-service", "channel", "PUSH").increment();
                    return;
                }
                ProviderResult result = Timer.builder("provider_request_duration_seconds")
                        .tag("channel", "PUSH")
                        .tag("provider", "test-push")
                        .register(meterRegistry)
                        .record(() -> provider.sendPush(new SendPushCommand(
                                job.destination(), job.subject(), job.body(), job.eventId().toString(), job.notificationId().toString())));
                repository.saveAttempt(job, result);
                meterRegistry.counter("worker_messages_processed_total", "service", "push-worker-service", "channel", "PUSH", "status", result.status()).increment();
                meterRegistry.counter("delivery_attempt_total", "channel", "PUSH", "provider", result.provider(), "status", result.status()).increment();
                if (!"SENT".equals(result.status())) {
                    meterRegistry.counter("provider_error_total", "channel", "PUSH", "provider", result.provider(), "error_code", String.valueOf(result.errorCode())).increment();
                }
            } catch (RuntimeException exception) {
                meterRegistry.counter("worker_messages_failed_total", "service", "push-worker-service", "channel", "PUSH", "reason", exception.getClass().getSimpleName()).increment();
                throw exception;
            } finally {
                processingTimer.stop(Timer.builder("worker_processing_duration_seconds")
                        .tag("service", "push-worker-service")
                        .tag("channel", "PUSH")
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
            MDC.put("channel", "PUSH");
        }
    }

    @Repository
    static class DeliveryRepository {
        private final JdbcTemplate jdbc;
        private final ObjectMapper objectMapper;

        DeliveryRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
            this.jdbc = jdbc;
            this.objectMapper = objectMapper;
        }

        @Transactional
        boolean markProcessing(UUID eventId) {
            try {
                jdbc.update("INSERT INTO processed_events (event_id, processed_at) VALUES (?, ?)", eventId, ts(Instant.now()));
                return true;
            } catch (DuplicateKeyException duplicate) {
                return false;
            }
        }

        @Transactional
        void saveAttempt(DeliveryJob job, ProviderResult result) {
            jdbc.update("""
                    INSERT INTO delivery_attempts (
                        id, event_id, notification_id, delivery_id, channel, destination, provider,
                        provider_message_id, status, raw_response, error_code, error_message, sent_at, created_at
                    )
                    VALUES (?, ?, ?, ?, 'PUSH', ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(), job.eventId(), job.notificationId(), job.deliveryId(), job.destination(),
                    result.provider(), result.providerMessageId(), result.status(), writeJson(result.rawResponse()),
                    result.errorCode(), result.errorMessage(), ts(result.sentAt()), ts(Instant.now()));
        }

        private String writeJson(Object value) {
            try {
                return objectMapper.writeValueAsString(value == null ? Map.of() : value);
            } catch (Exception exception) {
                throw new IllegalArgumentException("Could not write JSON", exception);
            }
        }
    }

    @Bean
    TestPushProvider testPushProvider(
            JdbcTemplate jdbc,
            @Value("${TEST_PROVIDER_FAILURE_RATE:0.0}") double failureRate,
            @Value("${TEST_PROVIDER_LATENCY_MS:0}") long latencyMs) {
        return new TestPushProvider(jdbc, failureRate, latencyMs);
    }

    @Bean
    @Primary
    PushProvider pushProvider(TestPushProvider provider) {
        return provider;
    }

    record Health(String status, Instant checkedAt) {
    }

    record WorkerStatus(String channel, String status, String idempotencyRule) {
    }

    interface PushProvider {
        ProviderResult sendPush(SendPushCommand command);
    }

    record DeliveryJob(UUID eventId, UUID notificationId, UUID deliveryId, String channel, String destination, String subject, String body, String priority, String correlationId) {
    }

    record SendPushCommand(@NotBlank String recipient, @NotBlank String title, @NotBlank String body, String eventId, String notificationId) {
    }

    record ProviderResult(String provider, String providerMessageId, String status, Object rawResponse, String errorCode, String errorMessage, Instant sentAt) {
    }

    record TestPushMessage(UUID id, String recipient, String title, String body, ProviderResult result, Instant createdAt) {
    }

    static class TestPushProvider implements PushProvider {
        private final JdbcTemplate jdbc;
        private final double failureRate;
        private final long latencyMs;
        private final java.util.List<TestPushMessage> memory = new java.util.concurrent.CopyOnWriteArrayList<>();

        TestPushProvider(JdbcTemplate jdbc, double failureRate, long latencyMs) {
            this.jdbc = jdbc;
            this.failureRate = failureRate;
            this.latencyMs = latencyMs;
        }

        TestPushProvider(double failureRate, long latencyMs) {
            this(null, failureRate, latencyMs);
        }

        @Override
        public ProviderResult sendPush(SendPushCommand command) {
            simulateLatency();
            ProviderResult result = simulate(command.recipient());
            UUID id = UUID.randomUUID();
            if (jdbc != null) {
                jdbc.update("""
                        INSERT INTO test_push_messages (id, recipient, title, body, status, error_code, error_message, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, id, command.recipient(), command.title(), command.body(), result.status(), result.errorCode(), result.errorMessage(), ts(Instant.now()));
            } else {
                memory.add(new TestPushMessage(id, command.recipient(), command.title(), command.body(), result, Instant.now()));
            }
            return result;
        }

        List<TestPushMessage> messages() {
            if (jdbc == null) {
                return memory;
            }
            return jdbc.query("""
                    SELECT id, recipient, title, body, status, error_code, error_message, created_at
                    FROM test_push_messages
                    ORDER BY created_at DESC
                    """, this::map);
        }

        TestPushMessage get(UUID id) {
            if (jdbc == null) {
                return memory.stream().filter(message -> message.id().equals(id)).findFirst().orElse(null);
            }
            return jdbc.queryForObject("""
                    SELECT id, recipient, title, body, status, error_code, error_message, created_at
                    FROM test_push_messages
                    WHERE id = ?
                    """, this::map, id);
        }

        void clear() {
            if (jdbc != null) {
                jdbc.update("DELETE FROM test_push_messages");
            } else {
                memory.clear();
            }
        }

        private TestPushMessage map(ResultSet rs, int rowNum) throws SQLException {
            ProviderResult result = new ProviderResult(
                    "test-push",
                    null,
                    rs.getString("status"),
                    Map.of("stored", true),
                    rs.getString("error_code"),
                    rs.getString("error_message"),
                    rs.getTimestamp("created_at").toInstant());
            return new TestPushMessage(
                    rs.getObject("id", UUID.class),
                    rs.getString("recipient"),
                    rs.getString("title"),
                    rs.getString("body"),
                    result,
                    rs.getTimestamp("created_at").toInstant());
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
            return new ProviderResult("test-push", UUID.randomUUID().toString(), "SENT", Map.of("accepted", true), null, null, Instant.now());
        }

        private ProviderResult failed(String code, String message) {
            return new ProviderResult("test-push", null, "FAILED", Map.of("accepted", false), code, message, Instant.now());
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
