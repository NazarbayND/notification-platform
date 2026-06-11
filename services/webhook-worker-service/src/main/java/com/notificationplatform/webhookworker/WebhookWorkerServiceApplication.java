package com.notificationplatform.webhookworker;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
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
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class WebhookWorkerServiceApplication {

    static final String DELIVERY_EXCHANGE = "notification.delivery";
    static final String WEBHOOK_QUEUE = "delivery.webhook";

    public static void main(String[] args) {
        SpringApplication.run(WebhookWorkerServiceApplication.class, args);
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
    Queue webhookQueue() {
        return new Queue(WEBHOOK_QUEUE, true);
    }

    @Bean
    Binding webhookBinding(Queue webhookQueue, DirectExchange deliveryExchange) {
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
    @RequestMapping("/worker")
    static class WorkerController {
        private final WebhookProvider provider;

        WorkerController(WebhookProvider provider) {
            this.provider = provider;
        }

        @GetMapping("/status")
        WorkerStatus status() {
            return new WorkerStatus("WEBHOOK", "READY", "Idempotency key: eventId or notificationId + channel");
        }

        @PostMapping("/test-send")
        ProviderResult testSend(@Valid @RequestBody SendWebhookCommand command) {
            return provider.sendWebhook(command);
        }
    }

    @RestController
    static class LocalWebhookReceiverController {
        private final LocalWebhookStore store;

        LocalWebhookReceiverController(LocalWebhookStore store) {
            this.store = store;
        }

        @PostMapping("/webhooks/test")
        ReceivedWebhook receive(HttpServletRequest request, @RequestBody(required = false) String body) {
            return store.record(request.getMethod(), request.getRequestURI(), headers(request), body);
        }

        @GetMapping("/received-webhooks")
        List<ReceivedWebhook> received() {
            return store.all();
        }

        @DeleteMapping("/received-webhooks")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void clear() {
            store.clear();
        }

        private Map<String, String> headers(HttpServletRequest request) {
            Map<String, String> headers = new LinkedHashMap<>();
            Enumeration<String> names = request.getHeaderNames();
            for (String name : Collections.list(names)) {
                headers.put(name, request.getHeader(name));
            }
            return headers;
        }
    }

    @org.springframework.stereotype.Component
    static class WebhookDeliveryConsumer {
        private final WebhookDeliveryRepository repository;
        private final WebhookProvider provider;

        WebhookDeliveryConsumer(WebhookDeliveryRepository repository, WebhookProvider provider) {
            this.repository = repository;
            this.provider = provider;
        }

        @RabbitListener(queues = WEBHOOK_QUEUE)
        void consume(DeliveryJob job) {
            if (!repository.markProcessing(job.eventId())) {
                return;
            }
            ProviderResult result = provider.sendWebhook(new SendWebhookCommand(
                    job.destination(), "POST", Map.of("x-correlation-id", job.correlationId()), job.body(),
                    job.eventId().toString(), job.notificationId().toString()));
            repository.saveAttempt(job, result);
        }
    }

    @Bean
    LocalWebhookStore localWebhookStore(JdbcTemplate jdbc, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new LocalWebhookStore(jdbc, objectMapper);
    }

    @Bean
    WebhookProvider webhookProvider(
            LocalWebhookStore store,
            @Value("${TEST_PROVIDER_FAILURE_RATE:0.0}") double failureRate,
            @Value("${TEST_PROVIDER_LATENCY_MS:0}") long latencyMs) {
        return new TestWebhookProvider(store, failureRate, latencyMs);
    }

    record Health(String status, Instant checkedAt) {
    }

    record WorkerStatus(String channel, String status, String idempotencyRule) {
    }

    interface WebhookProvider {
        ProviderResult sendWebhook(SendWebhookCommand command);
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

    record SendWebhookCommand(@NotBlank String url, String method, Map<String, String> headers, String body, String eventId, String notificationId) {
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

    record ReceivedWebhook(
            UUID id,
            String method,
            String path,
            Map<String, String> headers,
            String body,
            Instant createdAt) {
    }

    static class LocalWebhookStore {
        private final ConcurrentMap<UUID, ReceivedWebhook> received = new ConcurrentHashMap<>();
        private final JdbcTemplate jdbc;
        private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

        LocalWebhookStore(JdbcTemplate jdbc, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
            this.jdbc = jdbc;
            this.objectMapper = objectMapper;
        }

        LocalWebhookStore() {
            this(null, new com.fasterxml.jackson.databind.ObjectMapper());
        }

        ReceivedWebhook record(String method, String path, Map<String, String> headers, String body) {
            UUID id = UUID.randomUUID();
            ReceivedWebhook webhook = new ReceivedWebhook(id, method, path, headers, body, Instant.now());
            if (jdbc != null) {
                jdbc.update("""
                        INSERT INTO received_webhooks (id, method, path, headers, body, created_at)
                        VALUES (?, ?, ?, ?::jsonb, ?, ?)
                        """, id, method, path, writeJson(headers), body, ts(webhook.createdAt()));
            } else {
                received.put(id, webhook);
            }
            return webhook;
        }

        List<ReceivedWebhook> all() {
            if (jdbc != null) {
                return jdbc.query("""
                        SELECT id, method, path, headers, body, created_at
                        FROM received_webhooks
                        ORDER BY created_at DESC
                        """, this::map);
            }
            return received.values().stream().toList();
        }

        void clear() {
            if (jdbc != null) {
                jdbc.update("DELETE FROM received_webhooks");
            } else {
                received.clear();
            }
        }

        private ReceivedWebhook map(ResultSet rs, int rowNum) throws SQLException {
            return new ReceivedWebhook(
                    rs.getObject("id", UUID.class),
                    rs.getString("method"),
                    rs.getString("path"),
                    readHeaders(rs.getString("headers")),
                    rs.getString("body"),
                    rs.getTimestamp("created_at").toInstant());
        }

        private Map<String, String> readHeaders(String json) {
            try {
                return objectMapper.readValue(json, Map.class);
            } catch (Exception exception) {
                throw new IllegalStateException("Could not read webhook headers", exception);
            }
        }

        private String writeJson(Object value) {
            try {
                return objectMapper.writeValueAsString(value == null ? Map.of() : value);
            } catch (Exception exception) {
                throw new IllegalArgumentException("Could not write JSON", exception);
            }
        }
    }

    @org.springframework.stereotype.Repository
    static class WebhookDeliveryRepository {
        private final JdbcTemplate jdbc;

        WebhookDeliveryRepository(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
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
                    VALUES (?, ?, ?, ?, 'WEBHOOK', ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(), job.eventId(), job.notificationId(), job.deliveryId(), job.destination(),
                    result.provider(), result.providerMessageId(), result.status(), "{\"response\":\"" + result.rawResponse() + "\"}",
                    result.errorCode(), result.errorMessage(), ts(result.sentAt()), ts(Instant.now()));
        }
    }

    static class TestWebhookProvider implements WebhookProvider {
        private final LocalWebhookStore store;
        private final double failureRate;
        private final long latencyMs;

        TestWebhookProvider(LocalWebhookStore store, double failureRate, long latencyMs) {
            this.store = store;
            this.failureRate = failureRate;
            this.latencyMs = latencyMs;
        }

        @Override
        public ProviderResult sendWebhook(SendWebhookCommand command) {
            simulateLatency();
            ProviderResult simulated = simulate(command.url());
            if ("SENT".equals(simulated.status())) {
                store.record(
                        command.method() == null ? "POST" : command.method(),
                        "/webhooks/test",
                        command.headers() == null ? Map.of() : command.headers(),
                        command.body());
            }
            return simulated;
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
            return new ProviderResult("test-webhook", UUID.randomUUID().toString(), "SENT", "received by local webhook store", null, null, Instant.now());
        }

        private ProviderResult failed(String code, String message) {
            return new ProviderResult("test-webhook", null, "FAILED", "rejected", code, message, Instant.now());
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
