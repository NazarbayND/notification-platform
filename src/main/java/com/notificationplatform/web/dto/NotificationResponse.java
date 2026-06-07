package com.notificationplatform.web.dto;

import com.notificationplatform.domain.entity.NotificationRequest;
import com.notificationplatform.domain.model.NotificationPriority;
import com.notificationplatform.domain.model.NotificationRequestStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record NotificationResponse(
    UUID id,
    UUID productId,
    UUID batchId,
    UUID templateId,
    String externalUserId,
    String idempotencyKey,
    String category,
    NotificationPriority priority,
    Map<String, Object> payload,
    Map<String, Object> recipient,
    NotificationRequestStatus status,
    Instant createdAt,
    Instant updatedAt
) {

    public static NotificationResponse from(NotificationRequest request) {
        UUID batchId = request.getBatch() == null ? null : request.getBatch().getId();
        return new NotificationResponse(
            request.getId(),
            request.getProduct().getId(),
            batchId,
            request.getTemplate().getId(),
            request.getExternalUserId(),
            request.getIdempotencyKey(),
            request.getCategory(),
            request.getPriority(),
            request.getPayload(),
            request.getRecipient(),
            request.getStatus(),
            request.getCreatedAt(),
            request.getUpdatedAt()
        );
    }
}
