package com.notificationplatform.application.delivery;

import com.notificationplatform.application.common.ResourceNotFoundException;
import com.notificationplatform.domain.entity.DeliveryAttempt;
import com.notificationplatform.domain.entity.NotificationDelivery;
import com.notificationplatform.domain.entity.NotificationRequest;
import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.DeliveryAttemptStatus;
import com.notificationplatform.domain.model.DeliveryStatus;
import com.notificationplatform.domain.model.NotificationRequestStatus;
import com.notificationplatform.domain.repository.DeliveryAttemptRepository;
import com.notificationplatform.domain.repository.NotificationDeliveryRepository;
import com.notificationplatform.domain.repository.NotificationRequestRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationDeliveryService {

    private static final int DEFAULT_POLL_LIMIT = 100;

    private final NotificationDeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final NotificationRequestRepository requestRepository;
    private final Clock clock;

    @Autowired
    public NotificationDeliveryService(
        NotificationDeliveryRepository deliveryRepository,
        DeliveryAttemptRepository deliveryAttemptRepository,
        NotificationRequestRepository requestRepository
    ) {
        this(deliveryRepository, deliveryAttemptRepository, requestRepository, Clock.systemUTC());
    }

    NotificationDeliveryService(
        NotificationDeliveryRepository deliveryRepository,
        DeliveryAttemptRepository deliveryAttemptRepository,
        NotificationRequestRepository requestRepository,
        Clock clock
    ) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.requestRepository = requestRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public NotificationDelivery getDelivery(UUID deliveryId) {
        Objects.requireNonNull(deliveryId, "Delivery id is required");
        return findDelivery(deliveryId);
    }

    @Transactional(readOnly = true)
    public NotificationDelivery getDeliveryForSending(UUID deliveryId) {
        Objects.requireNonNull(deliveryId, "Delivery id is required");
        return deliveryRepository.findByIdWithRequestAndTemplate(deliveryId)
            .orElseThrow(() -> new ResourceNotFoundException("Notification delivery not found: " + deliveryId));
    }

    @Transactional(readOnly = true)
    public List<NotificationDelivery> listDeliveries(UUID notificationRequestId) {
        Objects.requireNonNull(notificationRequestId, "Notification request id is required");
        return deliveryRepository.findByNotificationRequest_IdOrderByCreatedAtAsc(notificationRequestId);
    }

    @Transactional(readOnly = true)
    public List<NotificationDelivery> listDeliveries(
        UUID notificationRequestId,
        DeliveryStatus status,
        Channel channel,
        String provider,
        int limit
    ) {
        int requestedLimit = limit <= 0 ? DEFAULT_POLL_LIMIT : Math.min(limit, DEFAULT_POLL_LIMIT);
        return deliveryRepository.findAll(
            deliveryListSpec(notificationRequestId, status, channel, trimToNull(provider)),
            PageRequest.of(0, requestedLimit, Sort.by(Sort.Direction.DESC, "createdAt"))
        ).getContent();
    }

    @Transactional(readOnly = true)
    public long countPendingDeliveries() {
        return deliveryRepository.countByStatusIn(EnumSet.of(
            DeliveryStatus.PENDING,
            DeliveryStatus.SENDING,
            DeliveryStatus.PROCESSING,
            DeliveryStatus.RETRY_SCHEDULED
        ));
    }

    @Transactional(readOnly = true)
    public long countFailedDeliveries() {
        return deliveryRepository.countByStatusIn(EnumSet.of(DeliveryStatus.FAILED));
    }

    @Transactional(readOnly = true)
    public long countDeadLetteredDeliveries() {
        return deliveryRepository.countByStatusIn(EnumSet.of(DeliveryStatus.DEAD_LETTERED, DeliveryStatus.DLQ));
    }

    @Transactional(readOnly = true)
    public List<DeliveryAttempt> listAttempts(UUID deliveryId) {
        Objects.requireNonNull(deliveryId, "Delivery id is required");
        return deliveryAttemptRepository.findByNotificationDelivery_IdOrderByAttemptNumberAsc(deliveryId);
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

    @Transactional(readOnly = true)
    public List<NotificationDelivery> findReadyForRetry(int limit) {
        int requestedLimit = limit <= 0 ? DEFAULT_POLL_LIMIT : limit;
        return deliveryRepository.findReadyForAttempt(
            EnumSet.of(DeliveryStatus.RETRY_SCHEDULED),
            Instant.now(clock),
            PageRequest.of(0, requestedLimit)
        );
    }

    @Transactional
    public NotificationDelivery markProcessing(UUID deliveryId, Duration lockDuration) {
        return markSending(deliveryId, lockDuration);
    }

    @Transactional
    public NotificationDelivery markSending(UUID deliveryId, Duration lockDuration) {
        Objects.requireNonNull(deliveryId, "Delivery id is required");
        Duration effectiveLockDuration = lockDuration == null ? Duration.ofMinutes(5) : lockDuration;

        NotificationDelivery delivery = findDeliveryForUpdate(deliveryId);
        if (!isEligibleForSending(delivery)) {
            return delivery;
        }

        if (isExpired(delivery)) {
            delivery.setStatus(DeliveryStatus.SKIPPED);
            delivery.setLockedUntil(null);
            NotificationDelivery savedDelivery = deliveryRepository.save(delivery);
            refreshRequestStatus(savedDelivery.getNotificationRequest());
            return savedDelivery;
        }

        delivery.setStatus(DeliveryStatus.SENDING);
        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setLockedUntil(Instant.now(clock).plus(effectiveLockDuration));
        NotificationDelivery savedDelivery = deliveryRepository.save(delivery);

        DeliveryAttempt attempt = new DeliveryAttempt(savedDelivery, savedDelivery.getAttemptCount());
        attempt.setStartedAt(Instant.now(clock));
        attempt.setRequestPayload(attemptRequestPayload(savedDelivery));
        deliveryAttemptRepository.save(attempt);

        return savedDelivery;
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
        deliveryAttemptRepository.findByNotificationDelivery_IdAndAttemptNumber(
            savedDelivery.getId(),
            savedDelivery.getAttemptCount()
        ).ifPresent(attempt -> {
            attempt.setStatus(DeliveryAttemptStatus.SUCCEEDED);
            attempt.setProvider(trimToNull(command.provider()));
            attempt.setProviderMessageId(trimToNull(command.providerMessageId()));
            attempt.setResponsePayload(command.providerResponse() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(command.providerResponse()));
            attempt.setCompletedAt(Instant.now(clock));
            deliveryAttemptRepository.save(attempt);
        });
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
            delivery.setStatus(DeliveryStatus.DEAD_LETTERED);
            delivery.setFailedAt(Instant.now(clock));
            delivery.setNextAttemptAt(null);
        } else {
            delivery.setStatus(DeliveryStatus.RETRY_SCHEDULED);
            delivery.setNextAttemptAt(Instant.now(clock).plus(backoffForAttempt(delivery.getAttemptCount())));
        }

        NotificationDelivery savedDelivery = deliveryRepository.save(delivery);
        deliveryAttemptRepository.findByNotificationDelivery_IdAndAttemptNumber(
            savedDelivery.getId(),
            savedDelivery.getAttemptCount()
        ).ifPresent(attempt -> {
            attempt.setStatus(DeliveryAttemptStatus.FAILED);
            attempt.setErrorCode(trimToNull(command.errorCode()));
            attempt.setErrorMessage(trimToNull(command.errorMessage()));
            attempt.setCompletedAt(Instant.now(clock));
            deliveryAttemptRepository.save(attempt);
        });
        refreshRequestStatus(savedDelivery.getNotificationRequest());
        return savedDelivery;
    }

    private NotificationDelivery findDelivery(UUID deliveryId) {
        return deliveryRepository.findById(deliveryId)
            .orElseThrow(() -> new ResourceNotFoundException("Notification delivery not found: " + deliveryId));
    }

    private NotificationDelivery findDeliveryForUpdate(UUID deliveryId) {
        return deliveryRepository.findByIdForUpdate(deliveryId)
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
            .anyMatch(delivery -> delivery.getStatus() == DeliveryStatus.DEAD_LETTERED
                || delivery.getStatus() == DeliveryStatus.DLQ
                || delivery.getStatus() == DeliveryStatus.FAILED);
        boolean anyInProgress = deliveries.stream()
            .anyMatch(delivery -> delivery.getStatus() == DeliveryStatus.PENDING
                || delivery.getStatus() == DeliveryStatus.SENDING
                || delivery.getStatus() == DeliveryStatus.PROCESSING
                || delivery.getStatus() == DeliveryStatus.RETRY_SCHEDULED);

        if (allComplete) {
            request.setStatus(NotificationRequestStatus.COMPLETED);
        } else if (anyFailed && !anyInProgress) {
            request.setStatus(deliveries.size() == 1 ? NotificationRequestStatus.FAILED : NotificationRequestStatus.PARTIAL_FAILED);
        }

        requestRepository.save(request);
    }

    private Specification<NotificationDelivery> deliveryListSpec(
        UUID notificationRequestId,
        DeliveryStatus status,
        Channel channel,
        String provider
    ) {
        return (root, query, criteriaBuilder) -> {
            if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("notificationRequest", JoinType.INNER);
                root.fetch("template", JoinType.INNER);
                query.distinct(true);
            }

            List<Predicate> predicates = new ArrayList<>();
            if (notificationRequestId != null) {
                predicates.add(criteriaBuilder.equal(root.get("notificationRequest").get("id"), notificationRequestId));
            }
            if (status != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), status));
            }
            if (channel != null) {
                predicates.add(criteriaBuilder.equal(root.get("channel"), channel));
            }
            if (provider != null) {
                predicates.add(criteriaBuilder.like(
                    criteriaBuilder.lower(root.get("provider")),
                    "%" + provider.toLowerCase() + "%"
                ));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Duration backoffForAttempt(int attemptCount) {
        int exponent = Math.max(0, Math.min(attemptCount - 1, 6));
        return Duration.ofMinutes(1L << exponent);
    }

    private Map<String, Object> attemptRequestPayload(NotificationDelivery delivery) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notificationRequestId", delivery.getNotificationRequest().getId());
        payload.put("deliveryId", delivery.getId());
        payload.put("templateId", delivery.getTemplate().getId());
        payload.put("channel", delivery.getChannel());
        payload.put("destination", delivery.getDestination());
        payload.put("requestPayload", delivery.getNotificationRequest().getPayload());
        return payload;
    }

    private boolean isExpired(NotificationDelivery delivery) {
        Instant expiresAt = delivery.getExpiresAt();
        return expiresAt != null && !expiresAt.isAfter(Instant.now(clock));
    }

    private boolean isEligibleForSending(NotificationDelivery delivery) {
        if (delivery.getStatus() == DeliveryStatus.PENDING) {
            return true;
        }
        return delivery.getStatus() == DeliveryStatus.RETRY_SCHEDULED
            && delivery.getNextAttemptAt() != null
            && !delivery.getNextAttemptAt().isAfter(Instant.now(clock));
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
