package com.notificationplatform.smsworker;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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
import org.slf4j.MDC;

@SpringBootApplication
public class SmsWorkerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmsWorkerServiceApplication.class, args);
    }

    private static java.sql.Timestamp ts(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
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
        private final SmsProvider smsProvider;

        WorkerController(SmsProvider smsProvider) {
            this.smsProvider = smsProvider;
        }

        @GetMapping("/status")
        WorkerStatus status() {
            return new WorkerStatus("SMS", "READY", "Idempotency key: eventId");
        }

        @PostMapping("/test-send")
        ProviderResult testSend(@Valid @RequestBody SendSmsCommand command) {
            return smsProvider.sendSms(command);
        }
    }

    @RestController
    @RequestMapping("/test/sms-messages")
    static class TestSmsController {
        private final TestSmsProvider provider;

        TestSmsController(TestSmsProvider provider) {
            this.provider = provider;
        }

        @GetMapping
        List<TestSmsMessage> list() {
            return provider.messages();
        }

        @GetMapping("/{id}")
        TestSmsMessage get(@PathVariable UUID id) {
            return provider.get(id);
        }

        @DeleteMapping
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void clear() {
            provider.clear();
        }
    }

    @org.springframework.stereotype.Component
    static class SmsDeliveryProcessor {
        private final DeliveryRepository repository;
        private final SmsProvider provider;
        private final MeterRegistry meterRegistry;

        SmsDeliveryProcessor(DeliveryRepository repository, SmsProvider provider, MeterRegistry meterRegistry) {
            this.repository = repository;
            this.provider = provider;
            this.meterRegistry = meterRegistry;
        }

        void process(DeliveryJob job) {
            Timer.Sample processingTimer = Timer.start(meterRegistry);
            meterRegistry.counter("worker_messages_consumed_total", "service", "sms-worker-service", "channel", "SMS").increment();
            putMdc(job);
            try {
                if (!repository.markProcessing(job.eventId())) {
                    meterRegistry.counter("worker_duplicate_events_skipped_total", "service", "sms-worker-service", "channel", "SMS").increment();
                    return;
                }
                ProviderResult result = Timer.builder("provider_request_duration_seconds")
                        .tag("channel", "SMS")
                        .tag("provider", "test-sms")
                        .register(meterRegistry)
                        .record(() -> provider.sendSms(new SendSmsCommand(
                                job.destination(), job.body(), job.deliveryId().toString(), job.notificationId().toString())));
                repository.saveAttempt(job, result);
                meterRegistry.counter("worker_messages_processed_total", "service", "sms-worker-service", "channel", "SMS").increment();
                meterRegistry.counter("delivery_attempt_total", "channel", "SMS", "provider", result.provider(), "status", result.status()).increment();
                if (!"SENT".equals(result.status())) {
                    meterRegistry.counter("provider_error_total", "channel", "SMS", "provider", result.provider(), "errorCode", nullToUnknown(result.errorCode())).increment();
                }
            } catch (RuntimeException exception) {
                meterRegistry.counter("worker_messages_failed_total", "service", "sms-worker-service", "channel", "SMS", "reason", exception.getClass().getSimpleName()).increment();
                throw exception;
            } finally {
                processingTimer.stop(Timer.builder("worker_processing_duration_seconds")
                        .tag("service", "sms-worker-service")
                        .tag("channel", "SMS")
                        .register(meterRegistry));
                clearMdc();
            }
        }

        private void putMdc(DeliveryJob job) {
            MDC.put("eventId", job.eventId().toString());
            MDC.put("notificationId", job.notificationId().toString());
            MDC.put("channel", "SMS");
        }

        private void clearMdc() {
            MDC.remove("eventId");
            MDC.remove("notificationId");
            MDC.remove("channel");
        }

        private String nullToUnknown(String value) {
            return value == null || value.isBlank() ? "unknown" : value;
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
            int inserted=jdbc.update("INSERT INTO processed_events (event_id, processed_at) VALUES (?, ?) ON CONFLICT DO NOTHING",eventId,ts(Instant.now()));
            if(inserted==1)return true;
            int reclaimed=jdbc.update("""
                DELETE FROM processed_events p WHERE p.event_id=? AND p.processed_at < now()-interval '5 minutes'
                AND NOT EXISTS (SELECT 1 FROM delivery_attempts a WHERE a.event_id=p.event_id)""",eventId);
            return reclaimed==1&&jdbc.update("INSERT INTO processed_events (event_id, processed_at) VALUES (?, ?) ON CONFLICT DO NOTHING",eventId,ts(Instant.now()))==1;
        }

        @Transactional
        void saveAttempt(DeliveryJob job, ProviderResult result) {
            Instant now = Instant.now();
            jdbc.update("""
                    INSERT INTO delivery_attempts (
                        id, event_id, notification_id, delivery_id, channel, destination, provider,
                        provider_message_id, status, raw_response, error_code, error_message, sent_at, created_at
                    )
                    VALUES (?, ?, ?, ?, 'SMS', ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(), job.eventId(), job.notificationId(), job.deliveryId(), job.destination(),
                    result.provider(), result.providerMessageId(), result.status(), writeJson(result.rawResponse()),
                    result.errorCode(), result.errorMessage(), ts(result.sentAt()), ts(now));
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
    TestSmsProvider testSmsProvider(
            JdbcTemplate jdbc,
            @Value("${TEST_PROVIDER_FAILURE_RATE:0.0}") double failureRate,
            @Value("${TEST_PROVIDER_LATENCY_MS:0}") long latencyMs) {
        return new TestSmsProvider(jdbc, failureRate, latencyMs);
    }

    @Bean
    @Primary
    SmsProvider smsProvider(TestSmsProvider provider) {
        return provider;
    }

    record Health(String status, Instant checkedAt) {
    }

    record WorkerStatus(String channel, String status, String idempotencyRule) {
    }

    interface SmsProvider {
        ProviderResult sendSms(SendSmsCommand command);
    }

    record DeliveryJob(
            UUID eventId,
            UUID notificationId,
            UUID deliveryId,
            String destination,
            String subject,
            String body) {
    }

    record SendSmsCommand(@NotBlank String recipient, @NotBlank String body, String eventId, String notificationId) {
    }

    record ProviderResult(
            String provider,
            String providerMessageId,
            String status,
            Object rawResponse,
            String errorCode,
            String errorMessage,
            Instant sentAt) {
    }

    record TestSmsMessage(UUID id, String recipient, String body, ProviderResult result, Instant createdAt) {
    }

    static class TestSmsProvider implements SmsProvider {
        private final JdbcTemplate jdbc;
        private final double failureRate;
        private final long latencyMs;
        private final java.util.List<TestSmsMessage> memory = new java.util.concurrent.CopyOnWriteArrayList<>();

        TestSmsProvider(JdbcTemplate jdbc, double failureRate, long latencyMs) {
            this.jdbc = jdbc;
            this.failureRate = failureRate;
            this.latencyMs = latencyMs;
        }

        TestSmsProvider(double failureRate, long latencyMs) {
            this(null, failureRate, latencyMs);
        }

        @Override
        public ProviderResult sendSms(SendSmsCommand command) {
            simulateLatency();
            ProviderResult result = simulate(command.recipient());
            UUID id = UUID.randomUUID();
            if (jdbc != null) {
                jdbc.update("""
                        INSERT INTO test_sms_messages (id, recipient, body, status, error_code, error_message, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, id, command.recipient(), command.body(), result.status(), result.errorCode(), result.errorMessage(), ts(Instant.now()));
            } else {
                memory.add(new TestSmsMessage(id, command.recipient(), command.body(), result, Instant.now()));
            }
            return result;
        }

        List<TestSmsMessage> messages() {
            if (jdbc == null) {
                return memory;
            }
            return jdbc.query("""
                    SELECT id, recipient, body, status, error_code, error_message, created_at
                    FROM test_sms_messages
                    ORDER BY created_at DESC
                    """, this::map);
        }

        TestSmsMessage get(UUID id) {
            if (jdbc == null) {
                return memory.stream().filter(message -> message.id().equals(id)).findFirst().orElse(null);
            }
            return jdbc.queryForObject("""
                    SELECT id, recipient, body, status, error_code, error_message, created_at
                    FROM test_sms_messages
                    WHERE id = ?
                    """, this::map, id);
        }

        void clear() {
            if (jdbc != null) {
                jdbc.update("DELETE FROM test_sms_messages");
            } else {
                memory.clear();
            }
        }

        private TestSmsMessage map(ResultSet rs, int rowNum) throws SQLException {
            ProviderResult result = new ProviderResult(
                    "test-sms",
                    null,
                    rs.getString("status"),
                    Map.of("stored", true),
                    rs.getString("error_code"),
                    rs.getString("error_message"),
                    rs.getTimestamp("created_at").toInstant());
            return new TestSmsMessage(
                    rs.getObject("id", UUID.class),
                    rs.getString("recipient"),
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
            return new ProviderResult("test-sms", UUID.randomUUID().toString(), "SENT", Map.of("accepted", true), null, null, Instant.now());
        }

        private ProviderResult failed(String code, String message) {
            return new ProviderResult("test-sms", null, "FAILED", Map.of("accepted", false), code, message, Instant.now());
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
