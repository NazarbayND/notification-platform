package com.notificationplatform.emailworker;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
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
public class EmailWorkerServiceApplication {

    static final String DELIVERY_EXCHANGE = "notification.delivery";
    static final String EMAIL_QUEUE = "delivery.email";

    public static void main(String[] args) {
        SpringApplication.run(EmailWorkerServiceApplication.class, args);
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
    Queue emailQueue() {
        return new Queue(EMAIL_QUEUE, true);
    }

    @Bean
    Binding emailBinding(Queue emailQueue, DirectExchange deliveryExchange) {
        return BindingBuilder.bind(emailQueue).to(deliveryExchange).with("EMAIL");
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
        private final EmailProvider emailProvider;

        WorkerController(EmailProvider emailProvider) {
            this.emailProvider = emailProvider;
        }

        @GetMapping("/status")
        WorkerStatus status() {
            return new WorkerStatus("EMAIL", "READY", "Idempotency key: eventId");
        }

        @PostMapping("/test-send")
        ProviderResult testSend(@Valid @RequestBody SendEmailCommand command) {
            return emailProvider.sendEmail(command);
        }
    }

    @RestController
    @RequestMapping("/test/email-messages")
    static class TestEmailController {
        private final TestEmailProvider provider;

        TestEmailController(TestEmailProvider provider) {
            this.provider = provider;
        }

        @GetMapping
        List<TestEmailMessage> list() {
            return provider.messages();
        }

        @GetMapping("/{id}")
        TestEmailMessage get(@PathVariable UUID id) {
            return provider.get(id);
        }

        @DeleteMapping
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void clear() {
            provider.clear();
        }
    }

    @org.springframework.stereotype.Component
    static class EmailDeliveryConsumer {
        private final DeliveryRepository repository;
        private final EmailProvider provider;

        EmailDeliveryConsumer(DeliveryRepository repository, EmailProvider provider) {
            this.repository = repository;
            this.provider = provider;
        }

        @RabbitListener(queues = EMAIL_QUEUE)
        void consume(DeliveryJob job) {
            if (!repository.markProcessing(job.eventId())) {
                return;
            }
            ProviderResult result = provider.sendEmail(new SendEmailCommand(
                    job.destination(), job.subject(), job.body(), job.eventId().toString(), job.notificationId().toString()));
            repository.saveAttempt(job, result);
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
                    VALUES (?, ?, ?, ?, 'EMAIL', ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
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
    TestEmailProvider testEmailProvider(
            JdbcTemplate jdbc,
            @Value("${TEST_PROVIDER_FAILURE_RATE:0.0}") double failureRate,
            @Value("${TEST_PROVIDER_LATENCY_MS:0}") long latencyMs) {
        return new TestEmailProvider(jdbc, failureRate, latencyMs);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "EMAIL_PROVIDER", havingValue = "smtp")
    EmailProvider smtpEmailProvider(JavaMailSender mailSender) {
        return new SmtpEmailProvider(mailSender);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "EMAIL_PROVIDER", havingValue = "test", matchIfMissing = true)
    EmailProvider testEmailProviderAdapter(TestEmailProvider provider) {
        return provider;
    }

    record Health(String status, Instant checkedAt) {
    }

    record WorkerStatus(String channel, String status, String idempotencyRule) {
    }

    interface EmailProvider {
        ProviderResult sendEmail(SendEmailCommand command);
    }

    record DeliveryJob(UUID eventId, UUID notificationId, UUID deliveryId, String channel, String destination, String subject, String body, String priority, String correlationId) {
    }

    record SendEmailCommand(@NotBlank String recipient, @NotBlank String subject, @NotBlank String body, String eventId, String notificationId) {
    }

    record ProviderResult(String provider, String providerMessageId, String status, Object rawResponse, String errorCode, String errorMessage, Instant sentAt) {
    }

    record TestEmailMessage(UUID id, String recipient, String subject, String body, ProviderResult result, Instant createdAt) {
    }

    static class TestEmailProvider implements EmailProvider {
        private final JdbcTemplate jdbc;
        private final double failureRate;
        private final long latencyMs;

        TestEmailProvider(JdbcTemplate jdbc, double failureRate, long latencyMs) {
            this.jdbc = jdbc;
            this.failureRate = failureRate;
            this.latencyMs = latencyMs;
        }

        @Override
        public ProviderResult sendEmail(SendEmailCommand command) {
            simulateLatency();
            ProviderResult result = simulate(command.recipient());
            jdbc.update("""
                    INSERT INTO test_email_messages (id, recipient, subject, body, status, error_code, error_message, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), command.recipient(), command.subject(), command.body(), result.status(), result.errorCode(), result.errorMessage(), ts(Instant.now()));
            return result;
        }

        List<TestEmailMessage> messages() {
            return jdbc.query("""
                    SELECT id, recipient, subject, body, status, error_code, error_message, created_at
                    FROM test_email_messages
                    ORDER BY created_at DESC
                    """, this::map);
        }

        TestEmailMessage get(UUID id) {
            return jdbc.queryForObject("""
                    SELECT id, recipient, subject, body, status, error_code, error_message, created_at
                    FROM test_email_messages
                    WHERE id = ?
                    """, this::map, id);
        }

        void clear() {
            jdbc.update("DELETE FROM test_email_messages");
        }

        private TestEmailMessage map(ResultSet rs, int rowNum) throws SQLException {
            ProviderResult result = new ProviderResult(
                    "test-email",
                    null,
                    rs.getString("status"),
                    Map.of("stored", true),
                    rs.getString("error_code"),
                    rs.getString("error_message"),
                    rs.getTimestamp("created_at").toInstant());
            return new TestEmailMessage(
                    rs.getObject("id", UUID.class),
                    rs.getString("recipient"),
                    rs.getString("subject"),
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
            return new ProviderResult("test-email", UUID.randomUUID().toString(), "SENT", Map.of("accepted", true), null, null, Instant.now());
        }

        private ProviderResult failed(String code, String message) {
            return new ProviderResult("test-email", null, "FAILED", Map.of("accepted", false), code, message, Instant.now());
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

    static class SmtpEmailProvider implements EmailProvider {
        private final JavaMailSender mailSender;

        SmtpEmailProvider(JavaMailSender mailSender) {
            this.mailSender = mailSender;
        }

        @Override
        public ProviderResult sendEmail(SendEmailCommand command) {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(command.recipient());
            message.setSubject(command.subject());
            message.setText(command.body());
            mailSender.send(message);
            return new ProviderResult("smtp", UUID.randomUUID().toString(), "SENT", Map.of("smtp", true), null, null, Instant.now());
        }
    }
}
