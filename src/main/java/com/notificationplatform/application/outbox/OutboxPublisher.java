package com.notificationplatform.application.outbox;

import com.notificationplatform.application.observability.NotificationMetrics;
import com.notificationplatform.application.observability.MdcScope;
import com.notificationplatform.application.observability.NotificationTracing;
import com.notificationplatform.application.queue.DeliveryMessage;
import com.notificationplatform.application.queue.QueuePublisher;
import com.notificationplatform.domain.entity.NotificationDelivery;
import com.notificationplatform.domain.entity.OutboxEvent;
import com.notificationplatform.domain.model.NotificationPriority;
import com.notificationplatform.domain.model.OutboxEventStatus;
import com.notificationplatform.domain.repository.NotificationDeliveryRepository;
import com.notificationplatform.domain.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int DEFAULT_BATCH_SIZE = 1_000;

    private final OutboxEventRepository outboxEventRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final QueuePublisher queuePublisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final int batchSize;
    private final NotificationMetrics metrics;
    private final NotificationTracing tracing;

    @Autowired
    public OutboxPublisher(
        OutboxEventRepository outboxEventRepository,
        NotificationDeliveryRepository deliveryRepository,
        QueuePublisher queuePublisher,
        TransactionTemplate transactionTemplate,
        NotificationMetrics metrics,
        NotificationTracing tracing,
        @Value("${outbox.publisher.batch-size:${notification.outbox.publisher.batch-size:1000}}") int batchSize
    ) {
        this(
            outboxEventRepository,
            deliveryRepository,
            queuePublisher,
            transactionTemplate,
            Clock.systemUTC(),
            batchSize,
            metrics,
            tracing
        );
    }

    OutboxPublisher(
        OutboxEventRepository outboxEventRepository,
        NotificationDeliveryRepository deliveryRepository,
        QueuePublisher queuePublisher,
        TransactionTemplate transactionTemplate,
        Clock clock,
        int batchSize,
        NotificationMetrics metrics,
        NotificationTracing tracing
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.deliveryRepository = deliveryRepository;
        this.queuePublisher = queuePublisher;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.batchSize = batchSize <= 0 ? DEFAULT_BATCH_SIZE : batchSize;
        this.metrics = metrics;
        this.tracing = tracing;
    }

    @Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay-ms:${notification.outbox.publisher.fixed-delay-ms:100}}")
    public void publishPendingEvents() {
        BatchPublishResult result = publishBatch();
        if (result.fetchedCount() > 0) {
            log.info(
                "Outbox publish batch completed: fetched={}, durationMs={}, rowsMarkedPublished={}, failed={}",
                result.fetchedCount(),
                result.duration().toMillis(),
                result.publishedCount(),
                result.failedCount()
            );
        }
    }

    BatchPublishResult publishBatch() {
        long startedAtNanos = System.nanoTime();
        BatchPublishResult result = metrics.recordOutboxPublish(() -> tracing.observe("outbox.publish.batch", () ->
            transactionTemplate.execute(status -> publishLockedBatch())
        ));
        BatchPublishResult safeResult = result == null ? BatchPublishResult.empty() : result;
        return safeResult.withDuration(Duration.ofNanos(System.nanoTime() - startedAtNanos));
    }

    private BatchPublishResult publishLockedBatch() {
        Instant now = Instant.now(clock);
        List<OutboxEvent> events = outboxEventRepository.findReadyPendingEventsForPublishing(now, batchSize);
        metrics.recordOutboxPublishBatchSize(events.size());
        recordPublishLag(now, events);

        if (events.isEmpty()) {
            return BatchPublishResult.empty();
        }

        log.info("Outbox publish batch fetched: batchSize={}", events.size());

        Map<UUID, NotificationDelivery> deliveriesById = findDeliveriesById(events);
        List<UUID> publishedEventIds = new ArrayList<>();
        List<OutboxEvent> failedEvents = new ArrayList<>();

        for (OutboxEvent event : events) {
            try (MdcScope ignored = MdcScope.with(Map.of("outboxEventId", event.getId().toString()))) {
                try {
                    publishDeliveryMessages(event, deliveriesById);
                    publishedEventIds.add(event.getId());
                } catch (RuntimeException ex) {
                    scheduleRetry(event, ex);
                    failedEvents.add(event);
                    metrics.incrementOutboxEventsFailed();
                }
            }
        }

        if (!failedEvents.isEmpty()) {
            outboxEventRepository.saveAll(failedEvents);
        }

        int rowsMarkedPublished = 0;
        if (!publishedEventIds.isEmpty()) {
            rowsMarkedPublished = outboxEventRepository.markEventsPublished(
                publishedEventIds,
                OutboxEventStatus.PUBLISHED,
                OutboxEventStatus.PENDING,
                Instant.now(clock)
            );
            for (int index = 0; index < rowsMarkedPublished; index++) {
                metrics.incrementOutboxEventsPublished();
            }
        }

        return new BatchPublishResult(events.size(), rowsMarkedPublished, failedEvents.size(), Duration.ZERO);
    }

    void publishEvent(UUID eventId) {
        try (MdcScope ignored = MdcScope.with(Map.of("outboxEventId", eventId.toString()))) {
            metrics.recordOutboxPublish(() -> tracing.observe("outbox.publish", Map.of("outbox.event.id", eventId.toString()), () -> transactionTemplate.executeWithoutResult(status -> {
            OutboxEvent event = outboxEventRepository.findByIdForUpdate(eventId)
                .orElse(null);
            if (event == null || !isReady(event)) {
                return;
            }

            try {
                publishDeliveryMessages(event);
                event.setStatus(OutboxEventStatus.PUBLISHED);
                event.setPublishedAt(Instant.now(clock));
                event.setLastError(null);
                metrics.incrementOutboxEventsPublished();
            } catch (RuntimeException ex) {
                scheduleRetry(event, ex);
                metrics.incrementOutboxEventsFailed();
            }
            outboxEventRepository.save(event);
            })));
        }
    }

    private void publishDeliveryMessages(OutboxEvent event) {
        publishDeliveryMessages(event, Map.of());
    }

    private void publishDeliveryMessages(OutboxEvent event, Map<UUID, NotificationDelivery> deliveriesById) {
        NotificationPriority priority = parsePriority(event.getPayload().get("priority"));
        UUID notificationRequestId = parseUuid(event.getPayload().get("notificationRequestId"));

        for (UUID deliveryId : parseDeliveryIds(event.getPayload())) {
            NotificationDelivery delivery = deliveriesById.get(deliveryId);
            if (delivery == null) {
                delivery = deliveryRepository.findByIdWithRequestAndTemplate(deliveryId)
                    .orElseThrow(() -> new IllegalStateException("Notification delivery not found: " + deliveryId));
            }
            UUID requestId = notificationRequestId == null
                ? delivery.getNotificationRequest().getId()
                : notificationRequestId;

            queuePublisher.publish(
                priority,
                new DeliveryMessage(
                    requestId,
                    delivery.getId(),
                    delivery.getChannel(),
                    priority,
                    delivery.getAttemptCount() + 1
                )
            );
        }
    }

    private Map<UUID, NotificationDelivery> findDeliveriesById(List<OutboxEvent> events) {
        List<UUID> deliveryIds = events.stream()
            .map(OutboxEvent::getPayload)
            .map(OutboxPublisher::parseDeliveryIds)
            .flatMap(Collection::stream)
            .distinct()
            .toList();
        if (deliveryIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, NotificationDelivery> deliveriesById = new HashMap<>();
        deliveryRepository.findAllByIdInWithRequestAndTemplate(deliveryIds)
            .forEach(delivery -> deliveriesById.put(delivery.getId(), delivery));
        return deliveriesById;
    }

    private void scheduleRetry(OutboxEvent event, RuntimeException ex) {
        event.setAttemptCount(event.getAttemptCount() + 1);
        event.setAvailableAt(Instant.now(clock).plus(backoffForAttempt(event.getAttemptCount())));
        event.setLastError(trimToNull(ex.getMessage()));
    }

    private void recordPublishLag(Instant now, List<OutboxEvent> events) {
        double lagSeconds = events.stream()
            .map(OutboxEvent::getCreatedAt)
            .filter(Objects::nonNull)
            .min(Instant::compareTo)
            .map(oldestCreatedAt -> Duration.between(oldestCreatedAt, now))
            .map(Duration::toMillis)
            .map(milliseconds -> milliseconds / 1000.0)
            .orElse(0.0);
        metrics.recordOutboxPublishLagSeconds(lagSeconds);
    }

    private boolean isReady(OutboxEvent event) {
        return event.getStatus() == OutboxEventStatus.PENDING
            && !event.getAvailableAt().isAfter(Instant.now(clock));
    }

    private static List<UUID> parseDeliveryIds(Map<String, Object> payload) {
        Object value = payload.get("deliveryIds");
        if (!(value instanceof Collection<?> rawValues)) {
            return List.of();
        }

        List<UUID> deliveryIds = new ArrayList<>();
        for (Object rawValue : rawValues) {
            deliveryIds.add(parseUuid(rawValue));
        }
        return deliveryIds.stream()
            .filter(Objects::nonNull)
            .toList();
    }

    private static UUID parseUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return UUID.fromString(value.toString());
    }

    private static NotificationPriority parsePriority(Object value) {
        if (value instanceof NotificationPriority priority) {
            return priority;
        }
        if (value == null || value.toString().isBlank()) {
            return NotificationPriority.NORMAL;
        }
        return NotificationPriority.valueOf(value.toString());
    }

    private static Duration backoffForAttempt(int attemptCount) {
        int exponent = Math.max(0, Math.min(attemptCount - 1, 6));
        return Duration.ofSeconds(30L * (1L << exponent));
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    record BatchPublishResult(int fetchedCount, int publishedCount, int failedCount, Duration duration) {

        static BatchPublishResult empty() {
            return new BatchPublishResult(0, 0, 0, Duration.ZERO);
        }

        BatchPublishResult withDuration(Duration duration) {
            return new BatchPublishResult(fetchedCount, publishedCount, failedCount, duration);
        }
    }
}
