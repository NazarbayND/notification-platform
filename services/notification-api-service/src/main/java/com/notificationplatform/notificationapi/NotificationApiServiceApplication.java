package com.notificationplatform.notificationapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

        @GetMapping("/stats")
        NotificationStats stats() {
            Instant todayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
            long minutesToday = Math.max(1, Duration.between(todayStart, Instant.now()).toMinutes());
            return repository.stats(todayStart, minutesToday);
        }

        @GetMapping("/page")
        PageResponse<NotificationRecord> page(
                @RequestParam(required = false) String productId,
                @RequestParam(required = false) String status,
                @RequestParam(required = false) String channel,
                @RequestParam(required = false) String priority,
                @RequestParam(required = false) String dateFrom,
                @RequestParam(required = false) String dateTo,
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "50") int size) {
            return repository.findPage(productId, status, channel, priority, dateFrom, dateTo, page, size);
        }

        @GetMapping("/deliveries/page")
        PageResponse<DeliveryView> deliveriesPage(
                @RequestParam(required = false) UUID notificationId,
                @RequestParam(required = false) String status,
                @RequestParam(required = false) String channel,
                @RequestParam(required = false) String provider,
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "50") int size) {
            return repository.findDeliveriesPage(notificationId, status, channel, provider, page, size);
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
            StringBuilder sql = new StringBuilder("""
                    SELECT id, product_id, user_id, channel, template_key, priority, status, idempotency_key,
                           destination, variables, correlation_id, created_at, updated_at
                    FROM notifications
                    WHERE 1 = 1
                    """);
            List<Object> params = new ArrayList<>();
            if (status != null && !status.isBlank()) {
                sql.append(" AND status = ?");
                params.add(status.toUpperCase());
            }
            if (channel != null && !channel.isBlank()) {
                sql.append(" AND channel = ?");
                params.add(channel.toUpperCase());
            }
            sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
            params.add(limit);
            params.add(offset);
            return jdbc.query(sql.toString(), this::mapNotification, params.toArray());
        }

        PageResponse<NotificationRecord> findPage(
                String productId,
                String status,
                String channel,
                String priority,
                String dateFrom,
                String dateTo,
                int page,
                int size) {
            int limit = Math.max(1, Math.min(size, 200));
            int currentPage = Math.max(page, 0);
            int offset = currentPage * limit;
            StringBuilder where = new StringBuilder(" WHERE 1 = 1");
            List<Object> params = new ArrayList<>();
            addNotificationFilters(where, params, productId, status, channel, priority, dateFrom, dateTo);
            long total = count("SELECT count(*) FROM notifications" + where, params.toArray());
            List<Object> pageParams = new ArrayList<>(params);
            pageParams.add(limit);
            pageParams.add(offset);
            List<NotificationRecord> items = jdbc.query("""
                    SELECT id, product_id, user_id, channel, template_key, priority, status, idempotency_key,
                           destination, variables, correlation_id, created_at, updated_at
                    FROM notifications
                    """ + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?", this::mapNotification, pageParams.toArray());
            return new PageResponse<>(items, total, currentPage, limit);
        }

        PageResponse<DeliveryView> findDeliveriesPage(
                UUID notificationId,
                String status,
                String channel,
                String provider,
                int page,
                int size) {
            int limit = Math.max(1, Math.min(size, 200));
            int currentPage = Math.max(page, 0);
            int offset = currentPage * limit;
            StringBuilder where = new StringBuilder(" WHERE 1 = 1");
            List<Object> params = new ArrayList<>();
            addDeliveryFilters(where, params, notificationId, status, channel, provider);
            String from = """
                    FROM notification_deliveries d
                    LEFT JOIN outbox_events o ON o.payload ->> 'deliveryId' = d.id::text
                    """;
            long total = count("SELECT count(*) " + from + where, params.toArray());
            List<Object> pageParams = new ArrayList<>(params);
            pageParams.add(limit);
            pageParams.add(offset);
            List<DeliveryView> items = jdbc.query("""
                    SELECT COALESCE(o.event_id::text, d.id::text) AS id,
                           d.notification_id::text AS notification_request_id,
                           d.channel,
                           CASE d.status WHEN 'DEAD_LETTER' THEN 'DEAD_LETTERED' ELSE d.status END AS status,
                           'outbox' AS provider,
                           d.destination,
                           d.attempt_count,
                           d.max_attempts,
                           o.next_attempt_at,
                           o.last_error,
                           d.created_at
                    """ + from + where + " ORDER BY d.created_at DESC LIMIT ? OFFSET ?", this::mapDeliveryView, pageParams.toArray());
            return new PageResponse<>(items, total, currentPage, limit);
        }

        NotificationStats stats(Instant todayStart, long minutesToday) {
            long totalNotificationsToday = count("""
                    SELECT count(*)
                    FROM notifications
                    WHERE created_at >= ?
                    """, ts(todayStart));
            long sentCount = count("""
                    SELECT count(*)
                    FROM notifications
                    WHERE created_at >= ? AND status = 'SENT'
                    """, ts(todayStart));
            long failedCount = count("""
                    SELECT count(*)
                    FROM notifications
                    WHERE created_at >= ? AND status IN ('FAILED', 'PARTIAL_FAILED')
                    """, ts(todayStart));
            long pendingOutboxCount = count("""
                    SELECT count(*)
                    FROM outbox_events
                    WHERE status IN ('PENDING', 'PROCESSING')
                    """);
            long retryCount = count("""
                    SELECT count(*)
                    FROM outbox_events
                    WHERE status = 'FAILED'
                    """);
            long dlqCount = count("""
                    SELECT count(*)
                    FROM outbox_events
                    WHERE status = 'DEAD_LETTER'
                    """);
            long failedDeliveries = count("""
                    SELECT count(*)
                    FROM notification_deliveries
                    WHERE updated_at >= ? AND status IN ('FAILED', 'DEAD_LETTER')
                    """, ts(todayStart));
            long processedDeliveries = count("""
                    SELECT count(*)
                    FROM notification_deliveries
                    WHERE updated_at >= ? AND status IN ('SENT', 'FAILED', 'DEAD_LETTER')
                    """, ts(todayStart));
            double providerErrorRate = processedDeliveries == 0 ? 0.0 : (double) failedDeliveries / processedDeliveries;
            double throughputPerMinute = (double) totalNotificationsToday / minutesToday;
            return new NotificationStats(
                    totalNotificationsToday,
                    sentCount,
                    failedCount,
                    pendingOutboxCount,
                    retryCount,
                    dlqCount,
                    providerErrorRate,
                    throughputPerMinute);
        }

        private long count(String sql, Object... args) {
            Long value = jdbc.queryForObject(sql, Long.class, args);
            return value == null ? 0 : value;
        }

        private void addNotificationFilters(
                StringBuilder where,
                List<Object> params,
                String productId,
                String status,
                String channel,
                String priority,
                String dateFrom,
                String dateTo) {
            if (productId != null && !productId.isBlank()) {
                where.append(" AND product_id = ?");
                params.add(productId);
            }
            if (status != null && !status.isBlank()) {
                where.append(" AND status = ?");
                params.add(status.toUpperCase());
            }
            if (channel != null && !channel.isBlank()) {
                where.append(" AND channel = ?");
                params.add(channel.toUpperCase());
            }
            if (priority != null && !priority.isBlank()) {
                where.append(" AND priority = ?");
                params.add(priority.toUpperCase());
            }
            Instant from = startOfDate(dateFrom);
            if (from != null) {
                where.append(" AND created_at >= ?");
                params.add(ts(from));
            }
            Instant to = endOfDate(dateTo);
            if (to != null) {
                where.append(" AND created_at < ?");
                params.add(ts(to));
            }
        }

        private void addDeliveryFilters(
                StringBuilder where,
                List<Object> params,
                UUID notificationId,
                String status,
                String channel,
                String provider) {
            if (notificationId != null) {
                where.append(" AND d.notification_id = ?");
                params.add(notificationId);
            }
            if (status != null && !status.isBlank()) {
                where.append(" AND d.status = ?");
                params.add(deliveryStatusFilter(status));
            }
            if (channel != null && !channel.isBlank()) {
                where.append(" AND d.channel = ?");
                params.add(channel.toUpperCase());
            }
            if (provider != null && !provider.isBlank()) {
                where.append(" AND LOWER('outbox') LIKE ?");
                params.add("%" + provider.toLowerCase() + "%");
            }
        }

        private String deliveryStatusFilter(String status) {
            return switch (status.toUpperCase()) {
                case "DEAD_LETTERED", "DLQ" -> "DEAD_LETTER";
                default -> status.toUpperCase();
            };
        }

        private Instant startOfDate(String date) {
            if (date == null || date.isBlank()) {
                return null;
            }
            try {
                return LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (RuntimeException exception) {
                return null;
            }
        }

        private Instant endOfDate(String date) {
            if (date == null || date.isBlank()) {
                return null;
            }
            try {
                return LocalDate.parse(date).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (RuntimeException exception) {
                return null;
            }
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

        private DeliveryView mapDeliveryView(ResultSet rs, int rowNum) throws SQLException {
            java.sql.Timestamp nextAttemptAt = rs.getTimestamp("next_attempt_at");
            return new DeliveryView(
                    rs.getString("id"),
                    rs.getString("notification_request_id"),
                    "",
                    rs.getString("channel"),
                    rs.getString("status"),
                    rs.getString("provider"),
                    rs.getString("destination"),
                    rs.getInt("attempt_count"),
                    rs.getInt("max_attempts"),
                    nextAttemptAt == null ? null : nextAttemptAt.toInstant(),
                    rs.getString("last_error"),
                    rs.getTimestamp("created_at").toInstant());
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

    record NotificationStats(
            long totalNotificationsToday,
            long sentCount,
            long failedCount,
            long pendingOutboxCount,
            long retryCount,
            long dlqCount,
            double providerErrorRate,
            double throughputPerMinute) {
    }

    record PageResponse<T>(List<T> items, long total, int page, int size) {
    }

    record DeliveryView(
            String id,
            String notificationRequestId,
            String templateId,
            String channel,
            String status,
            String provider,
            String destination,
            int attemptCount,
            int maxAttempts,
            Instant nextAttemptAt,
            String lastErrorMessage,
            Instant createdAt) {
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
