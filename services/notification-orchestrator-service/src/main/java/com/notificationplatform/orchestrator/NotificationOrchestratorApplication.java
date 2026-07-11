package com.notificationplatform.orchestrator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notificationplatform.contracts.AggregateChangedEvent;
import com.notificationplatform.contracts.DeliveryRequested;
import com.notificationplatform.contracts.NotificationRequested;
import com.notificationplatform.contracts.NotificationStatusChanged;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@EnableScheduling
@SpringBootApplication
public class NotificationOrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationOrchestratorApplication.class, args);
    }

    @Bean
    Jackson2JsonMessageConverter rabbitJsonConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @RestController
    static class ReferenceProjectionAdminController {
        private final OrchestratorRepository repository;
        ReferenceProjectionAdminController(OrchestratorRepository repository){this.repository=repository;}
        @PostMapping("/internal/projections/references/clear")
        Map<String,Object> clear(){repository.clearReferences();return Map.of("status","CLEARED","at",Instant.now());}
    }

    @RestController
    static class HealthController {
        @GetMapping({"/health/live", "/health/ready"})
        Map<String, Object> health() {
            return Map.of("status", "UP", "checkedAt", Instant.now());
        }
    }
}

@Component
class NotificationRequestConsumer {
    private final ObjectMapper objectMapper;
    private final OrchestrationService orchestrationService;

    NotificationRequestConsumer(ObjectMapper objectMapper, OrchestrationService orchestrationService) {
        this.objectMapper = objectMapper;
        this.orchestrationService = orchestrationService;
    }

    @KafkaListener(topics = "${notification.kafka.topics.requests:notification.requests.v1}",
            groupId = "${notification.kafka.groups.orchestrator:notification-orchestrator-v1}")
    void consume(String json) throws Exception {
        orchestrationService.orchestrate(objectMapper.readValue(json, NotificationRequested.class));
    }
}

@Service
class OrchestrationService {
    private final OrchestratorRepository repository;
    private final ReferenceResolver resolver;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final String deliveryBroker;

    OrchestrationService(
            OrchestratorRepository repository,
            ReferenceResolver resolver,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${notification.broker.delivery:kafka}") String deliveryBroker) {
        this.repository = repository;
        this.resolver = resolver;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.deliveryBroker = deliveryBroker;
    }

    @Transactional
    void orchestrate(NotificationRequested request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            if (!repository.markProcessed("notification-orchestrator", request.eventId())) {
                meterRegistry.counter("orchestrator_deduplicated_events_total").increment();
                return;
            }
            Optional<String> existingNotification = repository.claimIdempotency(
                    request.tenantId(), request.idempotencyKey(), request.notificationId());
            if (existingNotification.isPresent() && !existingNotification.get().equals(request.notificationId())) {
                meterRegistry.counter("orchestrator_deduplicated_events_total").increment();
                return;
            }

            enqueueStatus(request, "PROCESSING", null, null);
            int generated = 0;
            for (String channel : request.requestedChannels()) {
                String normalizedChannel = channel.toUpperCase();
                if (!resolver.preferenceAllowed(request, normalizedChannel)) {
                    continue;
                }
                RenderedContent rendered = resolver.render(request, normalizedChannel);
                String address = recipientAddress(request.recipient(), normalizedChannel);
                if (address == null || address.isBlank()) {
                    throw new OrchestrationRejection("MISSING_RECIPIENT_ADDRESS", "No recipient address for " + normalizedChannel);
                }
                String deliveryId = UUID.randomUUID().toString();
                String eventId = UUID.randomUUID().toString();
                if (!repository.createDelivery(deliveryId, request, normalizedChannel)) {
                    continue;
                }
                DeliveryRequested delivery = new DeliveryRequested(
                        eventId, request.notificationId(), deliveryId, request.tenantId(), request.recipient().userId(),
                        normalizedChannel, address, rendered.subject(), rendered.body(), 1, Instant.now(), 1);
                enqueueDelivery(request, delivery);
                generated++;
            }
            if (generated == 0) {
                enqueueStatus(request, "REJECTED", "NO_ENABLED_CHANNELS", "No requested channel is enabled");
                meterRegistry.counter("orchestrator_rejected_notifications_total", "reason", "no_enabled_channels").increment();
            } else {
                enqueueStatus(request, "SCHEDULED", null, null);
                meterRegistry.counter("orchestrator_generated_deliveries_total").increment(generated);
            }
            meterRegistry.counter("orchestrator_requests_processed_total").increment();
        } catch (OrchestrationRejection rejection) {
            enqueueStatus(request, "REJECTED", rejection.code(), rejection.getMessage());
            meterRegistry.counter("orchestrator_rejected_notifications_total", "reason", rejection.code()).increment();
        } finally {
            sample.stop(Timer.builder("orchestrator_processing_latency").register(meterRegistry));
        }
    }

    private void enqueueDelivery(NotificationRequested request, DeliveryRequested delivery) {
        String topic = "notification." + delivery.channel().toLowerCase().replace('_', '-') + ".v1";
        String key = request.tenantId() + ":" + delivery.recipientId();
        if ("rabbitmq".equalsIgnoreCase(deliveryBroker)) {
            Map<String, Object> rabbitJob = Map.of(
                    "eventId", delivery.eventId(), "notificationId", delivery.notificationId(),
                    "deliveryId", delivery.deliveryId(), "channel", delivery.channel(),
                    "destination", delivery.recipientAddress(), "subject", nullToEmpty(delivery.subject()),
                    "body", delivery.body(), "priority", "NORMAL", "correlationId", request.requestId());
            repository.enqueue(delivery.eventId(), "RABBIT", "notification.delivery", delivery.channel(), rabbitJob);
        } else {
            repository.enqueue(delivery.eventId(), "KAFKA", topic, key, delivery);
        }
    }

    private void enqueueStatus(NotificationRequested request, String status, String reasonCode, String reasonMessage) {
        NotificationStatusChanged event = new NotificationStatusChanged(
                UUID.randomUUID().toString(), request.notificationId(), request.tenantId(), status,
                reasonCode, reasonMessage, Instant.now(), 1);
        repository.enqueue(event.eventId(), "KAFKA", "notification.status-events.v1",
                request.tenantId() + ":" + request.recipient().userId(), event);
    }

    private String recipientAddress(NotificationRequested.Recipient recipient, String channel) {
        return switch (channel) {
            case "EMAIL" -> recipient.email();
            case "SMS" -> recipient.phone();
            case "PUSH" -> recipient.pushToken();
            case "WEBHOOK" -> recipient.webhookUrl();
            case "IN_APP" -> recipient.userId();
            default -> throw new OrchestrationRejection("UNSUPPORTED_CHANNEL", "Unsupported channel " + channel);
        };
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

@Service
class ReferenceResolver {
    private final OrchestratorRepository repository;
    private final RestClient templateClient;
    private final RestClient preferenceClient;
    private final boolean synchronousFallback;
    private final MeterRegistry meterRegistry;

    ReferenceResolver(
            OrchestratorRepository repository,
            RestClient.Builder restClientBuilder,
            MeterRegistry meterRegistry,
            @Value("${orchestrator.reference.sync-fallback-enabled:false}") boolean synchronousFallback,
            @Value("${TEMPLATE_SERVICE_URL:http://localhost:8082}") String templateUrl,
            @Value("${PREFERENCE_SERVICE_URL:http://localhost:8083}") String preferenceUrl) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.synchronousFallback = synchronousFallback;
        this.templateClient = restClientBuilder.clone().baseUrl(templateUrl).build();
        this.preferenceClient = restClientBuilder.clone().baseUrl(preferenceUrl).build();
    }

    boolean preferenceAllowed(NotificationRequested request, String channel) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Optional<Boolean> projected = repository.preference(
                    request.productId(), request.recipient().userId(), request.productId(), channel);
            if (projected.isPresent()) return projected.get();
            if (!synchronousFallback) {
                throw new OrchestrationRejection("PREFERENCE_PROJECTION_MISS", "Preference projection is not ready");
            }
            PreferenceDecision response = preferenceClient.get()
                    .uri(uri -> uri.path("/preferences/check")
                            .queryParam("userId", request.recipient().userId())
                            .queryParam("productId", request.productId())
                            .queryParam("channel", channel).build())
                    .retrieve().onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new OrchestrationRejection("PREFERENCE_LOOKUP_FAILED", "Preference lookup failed");
                    }).body(PreferenceDecision.class);
            return response != null && response.allowed();
        } finally {
            sample.stop(Timer.builder("orchestrator_preference_resolution_latency").register(meterRegistry));
        }
    }

    RenderedContent render(NotificationRequested request, String channel) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Optional<TemplateProjection> projected = repository.template(request.productId(), request.templateId(), channel);
            if (projected.isPresent()) return render(projected.get(), request.variables());
            if (!synchronousFallback) {
                throw new OrchestrationRejection("TEMPLATE_PROJECTION_MISS", "Template projection is not ready");
            }
            RenderedContent response = templateClient.post().uri("/templates/render")
                    .body(Map.of("productId", request.productId(), "templateKey", request.templateId(),
                            "channel", channel, "variables", request.variables()))
                    .retrieve().onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new OrchestrationRejection("TEMPLATE_RENDER_FAILED", "Template rendering failed");
                    }).body(RenderedContent.class);
            if (response == null) throw new OrchestrationRejection("TEMPLATE_RENDER_FAILED", "Empty template response");
            return response;
        } finally {
            sample.stop(Timer.builder("orchestrator_render_latency").register(meterRegistry));
        }
    }

    private RenderedContent render(TemplateProjection template, Map<String, Object> variables) {
        List<String> missing = template.requiredVariables().stream().filter(key -> !variables.containsKey(key)).toList();
        if (!missing.isEmpty()) {
            throw new OrchestrationRejection("MISSING_TEMPLATE_VARIABLES", "Missing template variables: " + missing);
        }
        String subject = replace(template.subject(), variables);
        String body = replace(template.body(), variables);
        return new RenderedContent(template.aggregateId(), subject, body, List.of());
    }

    private String replace(String source, Map<String, Object> variables) {
        String rendered = source == null ? "" : source;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
        }
        return rendered;
    }
}

@Component
class ReferenceProjectionConsumers {
    private final ObjectMapper mapper;
    private final OrchestratorRepository repository;

    ReferenceProjectionConsumers(ObjectMapper mapper, OrchestratorRepository repository) {
        this.mapper = mapper;
        this.repository = repository;
    }

    @KafkaListener(topics = "template.events.v1", groupId = "orchestrator-template-projection-v1")
    void template(String json) throws Exception {
        repository.applyTemplate(mapper.readValue(json, AggregateChangedEvent.class));
    }

    @KafkaListener(topics = "preference.events.v1", groupId = "orchestrator-preference-projection-v1")
    void preference(String json) throws Exception {
        repository.applyPreference(mapper.readValue(json, AggregateChangedEvent.class));
    }
}

@Repository
class OrchestratorRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    OrchestratorRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    boolean markProcessed(String consumer, String eventId) {
        return jdbc.update("INSERT INTO processed_events(consumer_name,event_id,processed_at) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                consumer, eventId, java.sql.Timestamp.from(Instant.now())) == 1;
    }

    Optional<String> claimIdempotency(String tenantId, String key, String notificationId) {
        int inserted = jdbc.update("INSERT INTO notification_idempotency(tenant_id,idempotency_key,notification_id,created_at) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                tenantId, key, notificationId, java.sql.Timestamp.from(Instant.now()));
        if (inserted == 1) return Optional.empty();
        return Optional.ofNullable(jdbc.queryForObject(
                "SELECT notification_id FROM notification_idempotency WHERE tenant_id=? AND idempotency_key=?",
                String.class, tenantId, key));
    }

    boolean createDelivery(String deliveryId, NotificationRequested request, String channel) {
        return jdbc.update("""
                INSERT INTO orchestration_deliveries(delivery_id,notification_id,tenant_id,recipient_id,channel,status,created_at)
                VALUES (?,?,?,?,?,'SCHEDULED',?) ON CONFLICT DO NOTHING
                """, deliveryId, request.notificationId(), request.tenantId(), request.recipient().userId(), channel,
                java.sql.Timestamp.from(Instant.now())) == 1;
    }

    void enqueue(String eventId, String targetType, String targetName, String key, Object payload) {
        try {
            jdbc.update("""
                    INSERT INTO orchestration_outbox(event_id,target_type,target_name,message_key,payload,status,created_at)
                    VALUES (?,?,?,?,?::jsonb,'PENDING',?) ON CONFLICT DO NOTHING
                    """, eventId, targetType, targetName, key, mapper.writeValueAsString(payload),
                    java.sql.Timestamp.from(Instant.now()));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Could not serialize orchestration event", exception);
        }
    }

    Optional<TemplateProjection> template(String tenant, String key, String channel) {
        List<TemplateProjection> values = jdbc.query("""
                SELECT aggregate_id,subject,body,required_variables FROM template_projection
                WHERE tenant_id=? AND template_key=? AND channel=? AND status='ACTIVE'
                """, (rs, row) -> new TemplateProjection(rs.getString(1), rs.getString(2), rs.getString(3), readList(rs.getString(4))),
                tenant, key, channel);
        return values.stream().findFirst();
    }

    Optional<Boolean> preference(String tenant, String user, String product, String channel) {
        List<Boolean> values = jdbc.query("""
                SELECT allowed FROM preference_projection WHERE tenant_id=? AND user_id=? AND product_id=? AND channel=?
                """, (rs, row) -> rs.getBoolean(1), tenant, user, product, channel);
        return values.stream().findFirst();
    }

    @Transactional
    void applyTemplate(AggregateChangedEvent event) {
        Map<String, Object> p = event.payload();
        jdbc.update("""
                INSERT INTO template_projection(tenant_id,template_key,channel,aggregate_id,aggregate_version,subject,body,required_variables,status,occurred_at)
                VALUES (?,?,?,?,?,?,?,?::jsonb,?,?)
                ON CONFLICT (tenant_id,template_key,channel) DO UPDATE SET
                  aggregate_id=EXCLUDED.aggregate_id,aggregate_version=EXCLUDED.aggregate_version,subject=EXCLUDED.subject,
                  body=EXCLUDED.body,required_variables=EXCLUDED.required_variables,status=EXCLUDED.status,occurred_at=EXCLUDED.occurred_at
                WHERE (template_projection.aggregate_id <> EXCLUDED.aggregate_id AND template_projection.occurred_at < EXCLUDED.occurred_at)
                   OR (template_projection.aggregate_id = EXCLUDED.aggregate_id AND template_projection.aggregate_version < EXCLUDED.aggregate_version)
                """, string(p,"productId"), string(p,"templateKey"), string(p,"channel"), event.aggregateId(),
                event.aggregateVersion(), string(p,"subject"), string(p,"body"), json(p.get("requiredVariables")),
                event.eventType().endsWith("Deleted") ? "DELETED" : string(p,"status"), java.sql.Timestamp.from(event.occurredAt()));
    }

    @Transactional
    void applyPreference(AggregateChangedEvent event) {
        Map<String, Object> p = event.payload();
        jdbc.update("""
                INSERT INTO preference_projection(tenant_id,user_id,product_id,channel,aggregate_id,aggregate_version,allowed,occurred_at)
                VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT (tenant_id,user_id,product_id,channel) DO UPDATE SET
                  aggregate_id=EXCLUDED.aggregate_id,aggregate_version=EXCLUDED.aggregate_version,
                  allowed=EXCLUDED.allowed,occurred_at=EXCLUDED.occurred_at
                WHERE (preference_projection.aggregate_id <> EXCLUDED.aggregate_id AND preference_projection.occurred_at < EXCLUDED.occurred_at)
                   OR (preference_projection.aggregate_id = EXCLUDED.aggregate_id AND preference_projection.aggregate_version < EXCLUDED.aggregate_version)
                """, string(p,"productId"), string(p,"userId"), string(p,"productId"), string(p,"channel"),
                event.aggregateId(), event.aggregateVersion(), !event.eventType().endsWith("Deleted") && Boolean.TRUE.equals(p.get("allowed")),
                java.sql.Timestamp.from(event.occurredAt()));
    }

    List<OutboxRecord> claimOutbox(int limit) {
        return jdbc.query("""
                WITH selected AS (
                  SELECT event_id FROM orchestration_outbox
                  WHERE status IN ('PENDING','FAILED','PROCESSING') AND next_attempt_at <= now()
                  ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE orchestration_outbox o SET status='PROCESSING',attempt_count=attempt_count+1,
                  next_attempt_at=now()+interval '1 minute'
                FROM selected WHERE o.event_id=selected.event_id
                RETURNING o.event_id,o.target_type,o.target_name,o.message_key,o.payload::text,o.attempt_count
                """, this::mapOutbox, limit);
    }

    void published(String eventId) {
        jdbc.update("UPDATE orchestration_outbox SET status='PUBLISHED',published_at=now(),last_error=NULL WHERE event_id=?", eventId);
    }

    void failed(OutboxRecord event, String error) {
        long delay = Math.min(300, 1L << Math.min(event.attempt(), 8));
        jdbc.update("""
                UPDATE orchestration_outbox SET status=?,last_error=?,next_attempt_at=now()+(? * interval '1 second') WHERE event_id=?
                """, event.attempt() >= 10 ? "DEAD_LETTER" : "FAILED", truncate(error), delay, event.eventId());
    }

    @Transactional
    void clearReferences(){jdbc.execute("TRUNCATE template_projection,preference_projection");}

    private OutboxRecord mapOutbox(ResultSet rs, int row) throws SQLException {
        return new OutboxRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6));
    }

    private List<String> readList(String json) {
        try { return mapper.readValue(json, STRING_LIST); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value == null ? List.of() : value); } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
    private String string(Map<String,Object> p, String key) { return String.valueOf(p.getOrDefault(key, "")); }
    private String truncate(String error) { return error == null ? "unknown" : error.substring(0, Math.min(2000, error.length())); }
}

@Component
class OrchestrationOutboxPublisher {
    private final OrchestratorRepository repository;
    private final KafkaTemplate<Object, Object> kafka;
    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    OrchestrationOutboxPublisher(OrchestratorRepository repository, KafkaTemplate<Object,Object> kafka,
            RabbitTemplate rabbit, ObjectMapper mapper, MeterRegistry meters) {
        this.repository = repository; this.kafka = kafka; this.rabbit = rabbit; this.mapper = mapper; this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${orchestrator.outbox.fixed-delay-ms:250}")
    @Transactional
    void publish() {
        for (OutboxRecord event : repository.claimOutbox(200)) {
            try {
                JsonNode payload = mapper.readTree(event.payload());
                if ("KAFKA".equals(event.targetType())) {
                    kafka.send(event.targetName(), event.messageKey(), payload).get(5, TimeUnit.SECONDS);
                } else {
                    Map<String,Object> job = mapper.convertValue(payload, new TypeReference<>() {});
                    rabbit.convertAndSend(event.targetName(), event.messageKey(), job);
                }
                repository.published(event.eventId());
                meters.counter("orchestrator_outbox_published_total", "target", event.targetType().toLowerCase()).increment();
            } catch (Exception exception) {
                repository.failed(event, exception.getMessage());
                meters.counter("orchestrator_outbox_failures_total", "target", event.targetType().toLowerCase()).increment();
            }
        }
    }
}

record TemplateProjection(String aggregateId, String subject, String body, List<String> requiredVariables) {}
record RenderedContent(String templateId, String subject, String body, List<String> missingVariables) {}
record PreferenceDecision(String userId, String productId, String channel, boolean allowed) {}
record OutboxRecord(String eventId, String targetType, String targetName, String messageKey, String payload, int attempt) {}

class OrchestrationRejection extends RuntimeException {
    private final String code;
    OrchestrationRejection(String code, String message) { super(message); this.code = code; }
    String code() { return code; }
}
