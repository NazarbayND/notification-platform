package com.notificationplatform.web.dto;

import com.notificationplatform.domain.entity.NotificationRequest;
import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.NotificationPriority;
import com.notificationplatform.domain.model.NotificationRequestStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record NotificationResponse(
    UUID id,
    UUID productId,
    UUID batchId,
    String templateKey,
    List<Channel> requestedChannels,
    String externalUserId,
    String idempotencyKey,
    String category,
    NotificationPriority priority,
    Map<String, Object> payload,
    Map<String, Object> recipient,
    NotificationRequestStatus status,
    Instant expiresAt,
    Instant createdAt,
    Instant updatedAt
) {

    public static NotificationResponse from(NotificationRequest request) {
        UUID batchId = request.getBatch() == null ? null : request.getBatch().getId();
        return new NotificationResponse(
            request.getId(),
            request.getProduct().getId(),
            batchId,
            request.getTemplateKey(),
            request.getRequestedChannels(),
            request.getExternalUserId(),
            request.getIdempotencyKey(),
            request.getCategory(),
            request.getPriority(),
            request.getPayload(),
            request.getRecipient(),
            request.getStatus(),
            request.getExpiresAt(),
            request.getCreatedAt(),
            request.getUpdatedAt()
        );
    }
}
