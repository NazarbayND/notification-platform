package com.notificationplatform.application.delivery;

import com.notificationplatform.application.common.ResourceNotFoundException;
import com.notificationplatform.domain.entity.NotificationDelivery;
import com.notificationplatform.domain.entity.NotificationRequest;
import com.notificationplatform.domain.model.DeliveryStatus;
import com.notificationplatform.domain.model.NotificationRequestStatus;
import com.notificationplatform.domain.repository.NotificationDeliveryRepository;
import com.notificationplatform.domain.repository.NotificationRequestRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationDeliveryService {

    private static final int DEFAULT_POLL_LIMIT = 100;

    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationRequestRepository requestRepository;
    private final Clock clock;

    public NotificationDeliveryService(
        NotificationDeliveryRepository deliveryRepository,
        NotificationRequestRepository requestRepository
    ) {
        this(deliveryRepository, requestRepository, Clock.systemUTC());
    }

    NotificationDeliveryService(
        NotificationDeliveryRepository deliveryRepository,
        NotificationRequestRepository requestRepository,
        Clock clock
    ) {
        this.deliveryRepository = deliveryRepository;
        this.requestRepository = requestRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public NotificationDelivery getDelivery(UUID deliveryId) {
        Objects.requireNonNull(deliveryId, "Delivery id is required");
        return findDelivery(deliveryId);
    }

    @Transactional(readOnly = true)
    public List<NotificationDelivery> listDeliveries(UUID notificationRequestId) {
        Objects.requireNonNull(notificationRequestId, "Notification request id is required");
        return deliveryRepository.findByNotificationRequest_IdOrderByCreatedAtAsc(notificationRequestId);
    }

    @Transactional(readOnly = true)
    public List<NotificationDelivery> findReadyForAttempt(int limit) {
        int requestedLimit = limit <= 0 ? DEFAULT_POLL_LIMIT : limit;
        return deliveryRepository.findReadyForAttempt(
            EnumSet.of(DeliveryStatus.PENDING, DeliveryStatus.RETRY_SCHEDULED),
            Instant.now(clock),
            PageRequest.of(0, requestedLimit)
        );
    }

    @Transactional
    public NotificationDelivery markProcessing(UUID deliveryId, Duration lockDuration) {
        Objects.requireNonNull(deliveryId, "Delivery id is required");
        Duration effectiveLockDuration = lockDuration == null ? Duration.ofMinutes(5) : lockDuration;

        NotificationDelivery delivery = findDelivery(deliveryId);
        delivery.setStatus(DeliveryStatus.PROCESSING);
        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setLockedUntil(Instant.now(clock).plus(effectiveLockDuration));
        return deliveryRepository.save(delivery);
    }

    @Transactional
    public NotificationDelivery recordSuccess(RecordDeliverySuccessCommand command) {
        Objects.requireNonNull(command, "Record delivery success command is required");
        Objects.requireNonNull(command.deliveryId(), "Delivery id is required");

        NotificationDelivery delivery = findDelivery(command.deliveryId());
        delivery.setProvider(trimToNull(command.provider()));
        delivery.setProviderMessageId(trimToNull(command.providerMessageId()));
        delivery.setProviderResponse(command.providerResponse() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(command.providerResponse()));
        delivery.setStatus(DeliveryStatus.SENT);
        delivery.setSentAt(Instant.now(clock));
        delivery.setLockedUntil(null);

        NotificationDelivery savedDelivery = deliveryRepository.save(delivery);
        refreshRequestStatus(savedDelivery.getNotificationRequest());
        return savedDelivery;
    }

    @Transactional
    public NotificationDelivery markDelivered(UUID deliveryId) {
        Objects.requireNonNull(deliveryId, "Delivery id is required");

        NotificationDelivery delivery = findDelivery(deliveryId);
        delivery.setStatus(DeliveryStatus.DELIVERED);
        delivery.setDeliveredAt(Instant.now(clock));
        delivery.setLockedUntil(null);

        NotificationDelivery savedDelivery = deliveryRepository.save(delivery);
        refreshRequestStatus(savedDelivery.getNotificationRequest());
        return savedDelivery;
    }

    @Transactional
    public NotificationDelivery recordFailure(RecordDeliveryFailureCommand command) {
        Objects.requireNonNull(command, "Record delivery failure command is required");
        Objects.requireNonNull(command.deliveryId(), "Delivery id is required");

        NotificationDelivery delivery = findDelivery(command.deliveryId());
        delivery.setLastErrorCode(trimToNull(command.errorCode()));
        delivery.setLastErrorMessage(trimToNull(command.errorMessage()));
        delivery.setLockedUntil(null);

        if (delivery.getAttemptCount() >= delivery.getMaxAttempts()) {
            delivery.setStatus(DeliveryStatus.DLQ);
            delivery.setFailedAt(Instant.now(clock));
            delivery.setNextAttemptAt(null);
        } else {
            delivery.setStatus(DeliveryStatus.RETRY_SCHEDULED);
            delivery.setNextAttemptAt(Instant.now(clock).plus(backoffForAttempt(delivery.getAttemptCount())));
        }

        NotificationDelivery savedDelivery = deliveryRepository.save(delivery);
        refreshRequestStatus(savedDelivery.getNotificationRequest());
        return savedDelivery;
    }

    private NotificationDelivery findDelivery(UUID deliveryId) {
        return deliveryRepository.findById(deliveryId)
            .orElseThrow(() -> new ResourceNotFoundException("Notification delivery not found: " + deliveryId));
    }

    private void refreshRequestStatus(NotificationRequest request) {
        List<NotificationDelivery> deliveries = deliveryRepository.findByNotificationRequest_IdOrderByCreatedAtAsc(request.getId());
        if (deliveries.isEmpty()) {
            return;
        }

        boolean allComplete = deliveries.stream()
            .allMatch(delivery -> delivery.getStatus() == DeliveryStatus.SENT
                || delivery.getStatus() == DeliveryStatus.DELIVERED
                || delivery.getStatus() == DeliveryStatus.SKIPPED);
        boolean anyFailed = deliveries.stream()
            .anyMatch(delivery -> delivery.getStatus() == DeliveryStatus.DLQ
                || delivery.getStatus() == DeliveryStatus.FAILED);
        boolean anyInProgress = deliveries.stream()
            .anyMatch(delivery -> delivery.getStatus() == DeliveryStatus.PENDING
                || delivery.getStatus() == DeliveryStatus.PROCESSING
                || delivery.getStatus() == DeliveryStatus.RETRY_SCHEDULED);

        if (allComplete) {
            request.setStatus(NotificationRequestStatus.COMPLETED);
        } else if (anyFailed && !anyInProgress) {
            request.setStatus(deliveries.size() == 1 ? NotificationRequestStatus.FAILED : NotificationRequestStatus.PARTIAL_FAILED);
        }

        requestRepository.save(request);
    }

    private static Duration backoffForAttempt(int attemptCount) {
        int exponent = Math.max(0, Math.min(attemptCount - 1, 6));
        return Duration.ofMinutes(1L << exponent);
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
