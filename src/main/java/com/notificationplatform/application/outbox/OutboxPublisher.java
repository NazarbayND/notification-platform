package com.notificationplatform.application.outbox;

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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OutboxPublisher {

    private static final int DEFAULT_BATCH_SIZE = 100;

    private final OutboxEventRepository outboxEventRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final QueuePublisher queuePublisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final int batchSize;

    @Autowired
    public OutboxPublisher(
        OutboxEventRepository outboxEventRepository,
        NotificationDeliveryRepository deliveryRepository,
        QueuePublisher queuePublisher,
        TransactionTemplate transactionTemplate,
        @Value("${notification.outbox.publisher.batch-size:100}") int batchSize
    ) {
        this(
            outboxEventRepository,
            deliveryRepository,
            queuePublisher,
            transactionTemplate,
            Clock.systemUTC(),
            batchSize
        );
    }

    OutboxPublisher(
        OutboxEventRepository outboxEventRepository,
        NotificationDeliveryRepository deliveryRepository,
        QueuePublisher queuePublisher,
        TransactionTemplate transactionTemplate,
        Clock clock,
        int batchSize
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.deliveryRepository = deliveryRepository;
        this.queuePublisher = queuePublisher;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.batchSize = batchSize <= 0 ? DEFAULT_BATCH_SIZE : batchSize;
    }

    @Scheduled(fixedDelayString = "${notification.outbox.publisher.fixed-delay:PT5S}")
    public void publishPendingEvents() {
        List<UUID> eventIds = outboxEventRepository.findAvailableEvents(
                OutboxEventStatus.PENDING,
                Instant.now(clock),
                PageRequest.of(0, batchSize)
            )
            .stream()
            .map(OutboxEvent::getId)
            .toList();

        for (UUID eventId : eventIds) {
            publishEvent(eventId);
        }
    }

    void publishEvent(UUID eventId) {
        transactionTemplate.executeWithoutResult(status -> {
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
            } catch (RuntimeException ex) {
                event.setAttemptCount(event.getAttemptCount() + 1);
                event.setAvailableAt(Instant.now(clock).plus(backoffForAttempt(event.getAttemptCount())));
                event.setLastError(trimToNull(ex.getMessage()));
            }
            outboxEventRepository.save(event);
        });
    }

    private void publishDeliveryMessages(OutboxEvent event) {
        NotificationPriority priority = parsePriority(event.getPayload().get("priority"));
        UUID notificationRequestId = parseUuid(event.getPayload().get("notificationRequestId"));

        for (UUID deliveryId : parseDeliveryIds(event.getPayload())) {
            NotificationDelivery delivery = deliveryRepository.findByIdWithRequestAndTemplate(deliveryId)
                .orElseThrow(() -> new IllegalStateException("Notification delivery not found: " + deliveryId));
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
}
