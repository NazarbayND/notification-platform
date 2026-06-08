package com.notificationplatform.application.notification;

import com.notificationplatform.application.common.ResourceNotFoundException;
import com.notificationplatform.application.preferences.UserPreferenceService;
import com.notificationplatform.domain.entity.NotificationBatch;
import com.notificationplatform.domain.entity.NotificationDelivery;
import com.notificationplatform.domain.entity.NotificationRequest;
import com.notificationplatform.domain.entity.NotificationTemplate;
import com.notificationplatform.domain.entity.OutboxEvent;
import com.notificationplatform.domain.entity.Product;
import com.notificationplatform.domain.model.BatchStatus;
import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.DeliveryStatus;
import com.notificationplatform.domain.model.NotificationPriority;
import com.notificationplatform.domain.model.NotificationRequestStatus;
import com.notificationplatform.domain.model.TemplateStatus;
import com.notificationplatform.domain.repository.NotificationBatchRepository;
import com.notificationplatform.domain.repository.NotificationDeliveryRepository;
import com.notificationplatform.domain.repository.NotificationRequestRepository;
import com.notificationplatform.domain.repository.NotificationTemplateRepository;
import com.notificationplatform.domain.repository.OutboxEventRepository;
import com.notificationplatform.domain.repository.ProductRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationSubmissionService {

    private static final int DEFAULT_LIST_LIMIT = 100;
    private static final String AGGREGATE_NOTIFICATION_REQUEST = "NOTIFICATION_REQUEST";
    private static final String EVENT_NOTIFICATION_ACCEPTED = "NotificationAccepted";
    private static final String EVENT_NOTIFICATION_SKIPPED = "NotificationSkipped";

    private final ProductRepository productRepository;
    private final NotificationTemplateRepository templateRepository;
    private final NotificationRequestRepository requestRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationBatchRepository batchRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final UserPreferenceService userPreferenceService;

    public NotificationSubmissionService(
        ProductRepository productRepository,
        NotificationTemplateRepository templateRepository,
        NotificationRequestRepository requestRepository,
        NotificationDeliveryRepository deliveryRepository,
        NotificationBatchRepository batchRepository,
        OutboxEventRepository outboxEventRepository,
        UserPreferenceService userPreferenceService
    ) {
        this.productRepository = productRepository;
        this.templateRepository = templateRepository;
        this.requestRepository = requestRepository;
        this.deliveryRepository = deliveryRepository;
        this.batchRepository = batchRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.userPreferenceService = userPreferenceService;
    }

    @Transactional
    public NotificationRequest createNotification(CreateNotificationCommand command) {
        Objects.requireNonNull(command, "Create notification command is required");
        Objects.requireNonNull(command.productId(), "Product id is required");
        normalizeRequestedChannels(command.requestedChannels());
        String idempotencyKey = normalizeRequired(command.idempotencyKey(), "Idempotency key is required");

        return requestRepository.findByProduct_IdAndIdempotencyKey(command.productId(), idempotencyKey)
            .orElseGet(() -> createNotification(command, null));
    }

    @Transactional
    public NotificationBatch createNotificationBatch(CreateNotificationBatchCommand command) {
        Objects.requireNonNull(command, "Create notification batch command is required");
        Objects.requireNonNull(command.productId(), "Product id is required");
        String idempotencyKey = normalizeRequired(command.idempotencyKey(), "Batch idempotency key is required");
        List<BatchNotificationItem> items = Objects.requireNonNull(command.items(), "Batch items are required");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Batch must contain at least one notification");
        }

        return batchRepository.findByProduct_IdAndIdempotencyKey(command.productId(), idempotencyKey)
            .orElseGet(() -> createNewBatch(command, idempotencyKey, items));
    }

    @Transactional(readOnly = true)
    public NotificationRequest getNotification(UUID notificationId) {
        Objects.requireNonNull(notificationId, "Notification id is required");
        return requestRepository.findById(notificationId)
            .orElseThrow(() -> new ResourceNotFoundException("Notification request not found: " + notificationId));
    }

    @Transactional(readOnly = true)
    public List<NotificationRequest> listNotifications(
        UUID productId,
        NotificationRequestStatus status,
        NotificationPriority priority,
        Instant createdFrom,
        Instant createdTo,
        int limit
    ) {
        int requestedLimit = limit <= 0 ? DEFAULT_LIST_LIMIT : Math.min(limit, DEFAULT_LIST_LIMIT);
        return requestRepository.findAll(
            notificationListSpec(productId, status, priority, createdFrom, createdTo),
            PageRequest.of(0, requestedLimit, Sort.by(Sort.Direction.DESC, "createdAt"))
        ).getContent();
    }

    @Transactional(readOnly = true)
    public long countNotifications() {
        return requestRepository.count();
    }

    @Transactional(readOnly = true)
    public NotificationBatch getBatch(UUID batchId) {
        Objects.requireNonNull(batchId, "Batch id is required");
        return batchRepository.findById(batchId)
            .orElseThrow(() -> new ResourceNotFoundException("Notification batch not found: " + batchId));
    }

    @Transactional(readOnly = true)
    public List<NotificationBatch> listBatches(UUID productId) {
        Objects.requireNonNull(productId, "Product id is required");
        return batchRepository.findByProduct_IdOrderByCreatedAtDesc(productId);
    }

    @Transactional(readOnly = true)
    public List<NotificationRequest> listBatchNotifications(UUID batchId) {
        Objects.requireNonNull(batchId, "Batch id is required");
        return requestRepository.findByBatch_IdOrderByCreatedAtAsc(batchId);
    }

    @Transactional(readOnly = true)
    public List<NotificationRequest> listUserNotifications(UUID productId, String externalUserId) {
        Objects.requireNonNull(productId, "Product id is required");
        String normalizedExternalUserId = normalizeRequired(externalUserId, "External user id is required");
        return requestRepository.findByProduct_IdAndExternalUserIdOrderByCreatedAtDesc(productId, normalizedExternalUserId);
    }

    private NotificationBatch createNewBatch(
        CreateNotificationBatchCommand command,
        String idempotencyKey,
        List<BatchNotificationItem> items
    ) {
        Product product = productRepository.findById(command.productId())
            .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + command.productId()));

        NotificationBatch batch = new NotificationBatch(product, idempotencyKey, items.size());
        batch.setStatus(BatchStatus.PROCESSING);
        NotificationBatch savedBatch = batchRepository.save(batch);

        int acceptedCount = 0;
        int failedCount = 0;

        for (BatchNotificationItem item : items) {
            try {
                createNotification(toNotificationCommand(command.productId(), item), savedBatch);
                acceptedCount++;
            } catch (RuntimeException ex) {
                failedCount++;
            }
        }

        savedBatch.setAcceptedCount(acceptedCount);
        savedBatch.setFailedCount(failedCount);
        if (failedCount == 0) {
            savedBatch.setStatus(BatchStatus.COMPLETED);
        } else if (acceptedCount == 0) {
            savedBatch.setStatus(BatchStatus.FAILED);
        } else {
            savedBatch.setStatus(BatchStatus.PARTIAL_FAILED);
        }

        return batchRepository.save(savedBatch);
    }

    private Specification<NotificationRequest> notificationListSpec(
        UUID productId,
        NotificationRequestStatus status,
        NotificationPriority priority,
        Instant createdFrom,
        Instant createdTo
    ) {
        return (root, query, criteriaBuilder) -> {
            if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("product", JoinType.INNER);
                root.fetch("batch", JoinType.LEFT);
                query.distinct(true);
            }

            List<Predicate> predicates = new ArrayList<>();
            if (productId != null) {
                predicates.add(criteriaBuilder.equal(root.get("product").get("id"), productId));
            }
            if (status != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), status));
            }
            if (priority != null) {
                predicates.add(criteriaBuilder.equal(root.get("priority"), priority));
            }
            if (createdFrom != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("createdAt"), createdFrom));
            }
            if (createdTo != null) {
                predicates.add(criteriaBuilder.lessThan(root.get("createdAt"), createdTo));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private NotificationRequest createNotification(CreateNotificationCommand command, NotificationBatch batch) {
        Objects.requireNonNull(command.productId(), "Product id is required");
        List<Channel> requestedChannels = normalizeRequestedChannels(command.requestedChannels());
        String templateKey = normalizeRequired(command.templateKey(), "Template key is required");
        String externalUserId = normalizeRequired(command.externalUserId(), "External user id is required");
        String idempotencyKey = normalizeRequired(command.idempotencyKey(), "Idempotency key is required");
        String category = normalizeRequired(command.category(), "Notification category is required");

        Optional<NotificationRequest> existingRequest = requestRepository.findByProduct_IdAndIdempotencyKey(
            command.productId(),
            idempotencyKey
        );
        if (existingRequest.isPresent()) {
            return existingRequest.get();
        }

        Product product = productRepository.findById(command.productId())
            .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + command.productId()));

        NotificationRequest request = new NotificationRequest(product, templateKey, externalUserId, idempotencyKey, category);
        request.setBatch(batch);
        request.setRequestedChannels(requestedChannels);
        request.setPriority(command.priority() == null ? NotificationPriority.NORMAL : command.priority());
        request.setPayload(copyMap(command.payload()));
        request.setRecipient(copyMap(command.recipient()));
        request.setExpiresAt(command.expiresAt());

        if (isExpired(command.expiresAt())) {
            request.setStatus(NotificationRequestStatus.SKIPPED);
            NotificationRequest savedRequest = requestRepository.save(request);
            outboxEventRepository.save(new OutboxEvent(
                AGGREGATE_NOTIFICATION_REQUEST,
                savedRequest.getId(),
                EVENT_NOTIFICATION_SKIPPED,
                requestEventPayload(savedRequest)
            ));
            return savedRequest;
        }

        List<NotificationDelivery> deliveries = new ArrayList<>();
        for (Channel channel : requestedChannels) {
            boolean channelEnabled = userPreferenceService.isChannelEnabled(
                command.productId(),
                externalUserId,
                category,
                channel
            );
            if (!channelEnabled) {
                continue;
            }

            NotificationTemplate template = templateRepository.findByProduct_IdAndTemplateKeyAndChannelAndStatus(
                command.productId(),
                templateKey,
                channel,
                TemplateStatus.ACTIVE
            ).orElseThrow(() -> new ResourceNotFoundException("Active template not found for channel: " + channel));

            String destination = resolveDestination(channel, request.getRecipient());
            NotificationDelivery delivery = new NotificationDelivery(request, template, channel, destination);
            delivery.setStatus(DeliveryStatus.PENDING);
            delivery.setExpiresAt(command.expiresAt());
            deliveries.add(delivery);
        }

        if (deliveries.isEmpty()) {
            request.setStatus(NotificationRequestStatus.SKIPPED);
            NotificationRequest savedRequest = requestRepository.save(request);
            outboxEventRepository.save(new OutboxEvent(
                AGGREGATE_NOTIFICATION_REQUEST,
                savedRequest.getId(),
                EVENT_NOTIFICATION_SKIPPED,
                requestEventPayload(savedRequest)
            ));
            return savedRequest;
        }

        request.setStatus(NotificationRequestStatus.DELIVERY_CREATED);
        NotificationRequest savedRequest = requestRepository.save(request);

        List<UUID> deliveryIds = new ArrayList<>();
        for (NotificationDelivery delivery : deliveries) {
            delivery.setNotificationRequest(savedRequest);
            NotificationDelivery savedDelivery = deliveryRepository.save(delivery);
            deliveryIds.add(savedDelivery.getId());
        }

        Map<String, Object> eventPayload = requestEventPayload(savedRequest);
        eventPayload.put("deliveryIds", deliveryIds);
        outboxEventRepository.save(new OutboxEvent(
            AGGREGATE_NOTIFICATION_REQUEST,
            savedRequest.getId(),
            EVENT_NOTIFICATION_ACCEPTED,
            eventPayload
        ));

        return savedRequest;
    }

    private static CreateNotificationCommand toNotificationCommand(UUID productId, BatchNotificationItem item) {
        Objects.requireNonNull(item, "Batch notification item is required");
        return new CreateNotificationCommand(
            productId,
            item.templateKey(),
            item.requestedChannels(),
            item.externalUserId(),
            item.idempotencyKey(),
            item.category(),
            item.priority(),
            item.payload(),
            item.recipient(),
            item.expiresAt()
        );
    }

    private static Map<String, Object> requestEventPayload(NotificationRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notificationRequestId", request.getId());
        payload.put("productId", request.getProduct().getId());
        payload.put("externalUserId", request.getExternalUserId());
        payload.put("category", request.getCategory());
        payload.put("priority", request.getPriority());
        payload.put("templateKey", request.getTemplateKey());
        payload.put("requestedChannels", request.getRequestedChannels());
        payload.put("status", request.getStatus());
        payload.put("expiresAt", request.getExpiresAt());
        return payload;
    }

    private static Map<String, Object> copyMap(Map<String, Object> value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(value);
    }

    private static List<Channel> normalizeRequestedChannels(List<Channel> requestedChannels) {
        if (requestedChannels == null || requestedChannels.isEmpty()) {
            throw new IllegalArgumentException("At least one requested channel is required");
        }

        Set<Channel> uniqueChannels = new LinkedHashSet<>();
        for (Channel channel : requestedChannels) {
            uniqueChannels.add(Objects.requireNonNull(channel, "Requested channel is required"));
        }
        return List.copyOf(uniqueChannels);
    }

    private static boolean isExpired(Instant expiresAt) {
        return expiresAt != null && !expiresAt.isAfter(Instant.now());
    }

    private static String resolveDestination(Channel channel, Map<String, Object> recipient) {
        String key = switch (channel) {
            case EMAIL -> "email";
            case SMS -> "phone";
            case PUSH -> "deviceToken";
            case IN_APP -> "inAppUserId";
        };

        Object value = recipient.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Recipient " + key + " is required for " + channel + " delivery");
        }
        return value.toString().trim();
    }

    private static String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
