package com.notificationplatform.application.outbox;

import com.notificationplatform.application.common.ResourceNotFoundException;
import com.notificationplatform.domain.entity.OutboxEvent;
import com.notificationplatform.domain.model.OutboxEventStatus;
import com.notificationplatform.domain.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxEventService {

    private static final int DEFAULT_POLL_LIMIT = 100;

    private final OutboxEventRepository outboxEventRepository;
    private final Clock clock;

    @Autowired
    public OutboxEventService(OutboxEventRepository outboxEventRepository) {
        this(outboxEventRepository, Clock.systemUTC());
    }

    OutboxEventService(OutboxEventRepository outboxEventRepository, Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<OutboxEvent> findPendingEvents(int limit) {
        int requestedLimit = limit <= 0 ? DEFAULT_POLL_LIMIT : limit;
        return outboxEventRepository.findAvailableEvents(
            OutboxEventStatus.PENDING,
            Instant.now(clock),
            PageRequest.of(0, requestedLimit)
        );
    }

    @Transactional
    public OutboxEvent markPublished(UUID eventId) {
        Objects.requireNonNull(eventId, "Outbox event id is required");
        OutboxEvent event = findEventForUpdate(eventId);
        event.setStatus(OutboxEventStatus.PUBLISHED);
        event.setPublishedAt(Instant.now(clock));
        event.setLastError(null);
        return outboxEventRepository.save(event);
    }

    @Transactional
    public OutboxEvent recordPublishFailure(UUID eventId, String errorMessage) {
        Objects.requireNonNull(eventId, "Outbox event id is required");
        OutboxEvent event = findEventForUpdate(eventId);
        event.setStatus(OutboxEventStatus.PENDING);
        event.setAttemptCount(event.getAttemptCount() + 1);
        event.setAvailableAt(Instant.now(clock).plus(backoffForAttempt(event.getAttemptCount())));
        event.setLastError(trimToNull(errorMessage));
        return outboxEventRepository.save(event);
    }

    @Transactional
    public OutboxEvent reschedule(UUID eventId, Instant availableAt) {
        Objects.requireNonNull(eventId, "Outbox event id is required");
        Objects.requireNonNull(availableAt, "Available at is required");
        OutboxEvent event = findEvent(eventId);
        event.setStatus(OutboxEventStatus.PENDING);
        event.setAvailableAt(availableAt);
        return outboxEventRepository.save(event);
    }

    private OutboxEvent findEvent(UUID eventId) {
        return outboxEventRepository.findById(eventId)
            .orElseThrow(() -> new ResourceNotFoundException("Outbox event not found: " + eventId));
    }

    private OutboxEvent findEventForUpdate(UUID eventId) {
        return outboxEventRepository.findByIdForUpdate(eventId)
            .orElseThrow(() -> new ResourceNotFoundException("Outbox event not found: " + eventId));
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
