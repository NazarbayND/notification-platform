package com.notificationplatform.notificationapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@SpringBootApplication
public class NotificationApiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApiServiceApplication.class, args);
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
    @RequestMapping("/notifications")
    static class NotificationController {
        private final NotificationSubmissionService service;
        private final NotificationRepository repository;

        NotificationController(NotificationSubmissionService service, NotificationRepository repository) {
            this.service = service;
            this.repository = repository;
        }

        @PostMapping
        ResponseEntity<NotificationAccepted> submit(
                @Valid @RequestBody NotificationRequest request,
                @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
            String resolvedCorrelationId = correlationId == null || correlationId.isBlank()
                    ? UUID.randomUUID().toString()
                    : correlationId;
            NotificationAccepted response = service.submit(request, resolvedCorrelationId);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .header("X-Correlation-Id", resolvedCorrelationId)
                    .body(response);
        }

        @GetMapping("/{notificationId}/status")
        NotificationStatus status(@PathVariable UUID notificationId) {
            NotificationRecord record = repository.findById(notificationId);
            return new NotificationStatus(record.id(), record.status(), record.channel(), record.updatedAt());
        }

        @GetMapping("/{notificationId}")
        NotificationRecord get(@PathVariable UUID notificationId) {
            return repository.findById(notificationId);
        }

        @GetMapping
        List<NotificationRecord> list(
                @RequestParam(required = false) String status,
                @RequestParam(required = false) String channel,
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "50") int size) {
            return repository.findAll(status, channel, page, size);
        }
    }

    @Service
    static class NotificationSubmissionService {
        private final NotificationRepository repository;
        private final ObjectMapper objectMapper;
        private final RestClient templateClient;
        private final RestClient preferenceClient;
        private final MeterRegistry meterRegistry;

        NotificationSubmissionService(
                NotificationRepository repository,
                ObjectMapper objectMapper,
                RestClient.Builder restClientBuilder,
                MeterRegistry meterRegistry,
                @Value("${TEMPLATE_SERVICE_URL:http://localhost:8082}") String templateServiceUrl,
                @Value("${PREFERENCE_SERVICE_URL:http://localhost:8083}") String preferenceServiceUrl) {
            this.repository = repository;
            this.objectMapper = objectMapper;
            this.meterRegistry = meterRegistry;
            this.templateClient = restClientBuilder.baseUrl(templateServiceUrl).build();
            this.preferenceClient = restClientBuilder.baseUrl(preferenceServiceUrl).build();
        }

        @Transactional
        NotificationAccepted submit(NotificationRequest request, String correlationId) {
            Timer.Sample requestTimer = Timer.start(meterRegistry);
            String channel = request.channel().toUpperCase();
            String priority = normalizePriority(request.priority());
            MDC.put("channel", channel);
            try {
                PreferenceDecision preference = Timer.builder("preference_check_duration_seconds")
                        .description("Duration of synchronous preference checks")
                        .register(meterRegistry)
                        .record(() -> preferenceClient.get()
                                .uri(uri -> uri.path("/preferences/check")
                                        .queryParam("userId", request.userId())
                                        .queryParam("productId", request.productId())
                                        .queryParam("channel", channel)
                                        .build())
                                .retrieve()
                                .body(PreferenceDecision.class));

                UUID notificationId = UUID.randomUUID();
                MDC.put("notificationId", notificationId.toString());
                Instant now = Instant.now();
                if (preference == null || !preference.allowed()) {
                    meterRegistry.counter("notification_rejected_total", "reason", "preference_denied").increment();
                    NotificationRecord skipped = new NotificationRecord(
                            notificationId,
                            request.productId(),
                            request.userId(),
                            channel,
                            request.templateKey(),
                            priority,
                            "SKIPPED",
                            request.idempotencyKey(),
                            request.destination(),
                            request.variables() == null ? Map.of() : request.variables(),
                            correlationId,
                            now,
                            now);
                    NotificationRecord saved = repository.insertNotification(skipped);
                    return new NotificationAccepted(saved.id(), saved.status(), correlationId, channel, null);
                }

                RenderedTemplate rendered = Timer.builder("template_render_duration_seconds")
                        .description("Duration of synchronous template render calls")
                        .register(meterRegistry)
                        .record(() -> templateClient.post()
                                .uri("/templates/render")
                                .body(new RenderTemplateRequest(request.productId(), request.templateKey(), channel, request.variables()))
                                .retrieve()
                                .body(RenderedTemplate.class));
                if (rendered == null) {
                    meterRegistry.counter("notification_rejected_total", "reason", "template_empty_response").increment();
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Template service returned no rendered template");
                }

                NotificationRecord record = new NotificationRecord(
                        notificationId,
                        request.productId(),
                        request.userId(),
                        channel,
                        request.templateKey(),
                        priority,
                        "ACCEPTED",
                        request.idempotencyKey(),
                        request.destination(),
                        request.variables() == null ? Map.of() : request.variables(),
                        correlationId,
                        now,
                        now);

                try {
                    repository.insertNotification(record);
                } catch (DuplicateKeyException duplicate) {
                    meterRegistry.counter("notification_rejected_total", "reason", "duplicate_idempotency_key").increment();
                    NotificationRecord existing = repository.findByProductIdAndIdempotencyKey(request.productId(), request.idempotencyKey());
                    return new NotificationAccepted(existing.id(), existing.status(), correlationId, existing.channel(), null);
                }

                UUID deliveryId = UUID.randomUUID();
                repository.insertDelivery(new DeliveryRecord(
                        deliveryId,
                        notificationId,
                        channel,
                        request.destination(),
                        rendered.subject(),
                        rendered.body(),
                        "PENDING",
                        0,
                        5,
                        now,
                        now));

                UUID eventId = UUID.randomUUID();
                MDC.put("eventId", eventId.toString());
                repository.insertOutbox(new OutboxEvent(
                        eventId,
                        "Notification",
                        notificationId.toString(),
                        "NotificationAccepted",
                        Map.of(
                                "eventId", eventId.toString(),
                                "notificationId", notificationId.toString(),
                                "deliveryId", deliveryId.toString(),
                                "channel", channel,
                                "destination", request.destination(),
                                "subject", rendered.subject(),
                                "body", rendered.body(),
                                "priority", priority,
                                "correlationId", correlationId),
                        "PENDING",
                        0,
                        10,
                        null,
                        now,
                        null,
                        null,
                        now,
                        now));

                meterRegistry.counter("notification_created_total", "channel", channel, "priority", priority).increment();
                return new NotificationAccepted(notificationId, "ACCEPTED", correlationId, channel, eventId);
            } finally {
                requestTimer.stop(Timer.builder("notification_request_duration_seconds")
                        .description("Notification submission duration")
                        .tag("channel", channel)
                        .tag("priority", priority)
                        .register(meterRegistry));
                MDC.remove("channel");
                MDC.remove("notificationId");
                MDC.remove("eventId");
            }
        }

        private String normalizePriority(String priority) {
            return priority == null || priority.isBlank() ? "NORMAL" : priority.toUpperCase();
        }
    }

    @Repository
    static class NotificationRepository {
        private final JdbcTemplate jdbc;
        private final ObjectMapper objectMapper;

        NotificationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
            this.jdbc = jdbc;
            this.objectMapper = objectMapper;
        }

        NotificationRecord insertNotification(NotificationRecord record) {
            jdbc.update("""
                    INSERT INTO notifications (
                        id, product_id, user_id, channel, template_key, priority, status, idempotency_key,
                        destination, variables, correlation_id, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                    """,
                    record.id(), record.productId(), record.userId(), record.channel(), record.templateKey(),
                    record.priority(), record.status(), record.idempotencyKey(), record.destination(),
                    writeJson(record.variables()), record.correlationId(), ts(record.createdAt()), ts(record.updatedAt()));
            return record;
        }

        void insertDelivery(DeliveryRecord delivery) {
            jdbc.update("""
                    INSERT INTO notification_deliveries (
                        id, notification_id, channel, destination, subject, body, status, attempt_count,
                        max_attempts, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    delivery.id(), delivery.notificationId(), delivery.channel(), delivery.destination(), delivery.subject(),
                    delivery.body(), delivery.status(), delivery.attemptCount(), delivery.maxAttempts(), ts(delivery.createdAt()), ts(delivery.updatedAt()));
        }

        void insertOutbox(OutboxEvent event) {
            jdbc.update("""
                    INSERT INTO outbox_events (
                        event_id, aggregate_type, aggregate_id, event_type, payload, status, attempt_count, max_attempts,
                        locked_until, next_attempt_at, last_error, published_at, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    event.eventId(), event.aggregateType(), event.aggregateId(), event.eventType(), writeJson(event.payload()),
                    event.status(), event.attemptCount(), event.maxAttempts(), ts(event.lockedUntil()), ts(event.nextAttemptAt()),
                    event.lastError(), ts(event.publishedAt()), ts(event.createdAt()), ts(event.updatedAt()));
        }

        NotificationRecord findById(UUID id) {
            try {
                return jdbc.queryForObject("""
                        SELECT id, product_id, user_id, channel, template_key, priority, status, idempotency_key,
                               destination, variables, correlation_id, created_at, updated_at
                        FROM notifications
                        WHERE id = ?
                        """, this::mapNotification, id);
            } catch (EmptyResultDataAccessException exception) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found: " + id);
            }
        }

        NotificationRecord findByProductIdAndIdempotencyKey(String productId, String idempotencyKey) {
            return jdbc.queryForObject("""
                    SELECT id, product_id, user_id, channel, template_key, priority, status, idempotency_key,
                           destination, variables, correlation_id, created_at, updated_at
                    FROM notifications
                    WHERE product_id = ? AND idempotency_key = ?
                    """, this::mapNotification, productId, idempotencyKey);
        }

        List<NotificationRecord> findAll(String status, String channel, int page, int size) {
            int limit = Math.max(1, Math.min(size, 200));
            int offset = Math.max(page, 0) * limit;
            if (status != null && channel != null) {
                return jdbc.query("""
                        SELECT id, product_id, user_id, channel, template_key, priority, status, idempotency_key,
                               destination, variables, correlation_id, created_at, updated_at
                        FROM notifications
                        WHERE status = ? AND channel = ?
                        ORDER BY created_at DESC
                        LIMIT ? OFFSET ?
                        """, this::mapNotification, status.toUpperCase(), channel.toUpperCase(), limit, offset);
            }
            return jdbc.query("""
                    SELECT id, product_id, user_id, channel, template_key, priority, status, idempotency_key,
                           destination, variables, correlation_id, created_at, updated_at
                    FROM notifications
                    ORDER BY created_at DESC
                    LIMIT ? OFFSET ?
                    """, this::mapNotification, limit, offset);
        }

        private NotificationRecord mapNotification(ResultSet rs, int rowNum) throws SQLException {
            return new NotificationRecord(
                    rs.getObject("id", UUID.class),
                    rs.getString("product_id"),
                    rs.getString("user_id"),
                    rs.getString("channel"),
                    rs.getString("template_key"),
                    rs.getString("priority"),
                    rs.getString("status"),
                    rs.getString("idempotency_key"),
                    rs.getString("destination"),
                    readMap(rs.getString("variables")),
                    rs.getString("correlation_id"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        }

        private Map<String, Object> readMap(String json) {
            try {
                return json == null ? Map.of() : objectMapper.readValue(json, Map.class);
            } catch (Exception exception) {
                throw new IllegalStateException("Could not read JSON", exception);
            }
        }

        private String writeJson(Object value) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (Exception exception) {
                throw new IllegalArgumentException("Could not write JSON", exception);
            }
        }
    }

    record Health(String status, Instant checkedAt) {
    }

    record NotificationRequest(
            @NotBlank String userId,
            @NotBlank String productId,
            @NotBlank String channel,
            @NotBlank String templateKey,
            Map<String, Object> variables,
            @NotBlank String idempotencyKey,
            @NotBlank String destination,
            String priority) {
    }

    record NotificationAccepted(UUID notificationId, String status, String correlationId, String channel, UUID outboxEventId) {
    }

    record NotificationStatus(UUID notificationId, String status, String channel, Instant updatedAt) {
    }

    record NotificationRecord(
            UUID id,
            String productId,
            String userId,
            String channel,
            String templateKey,
            String priority,
            String status,
            String idempotencyKey,
            String destination,
            Map<String, Object> variables,
            String correlationId,
            Instant createdAt,
            Instant updatedAt) {
    }

    record DeliveryRecord(
            UUID id,
            UUID notificationId,
            String channel,
            String destination,
            String subject,
            String body,
            String status,
            int attemptCount,
            int maxAttempts,
            Instant createdAt,
            Instant updatedAt) {
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

    record RenderTemplateRequest(String productId, String templateKey, String channel, Map<String, Object> variables) {
    }

    record RenderedTemplate(UUID templateId, String subject, String body, List<String> missingVariables) {
    }

    record PreferenceDecision(String userId, String productId, String channel, boolean allowed) {
    }
}
