package com.notificationplatform.web.dto;

import com.notificationplatform.domain.entity.NotificationBatch;
import com.notificationplatform.domain.model.BatchStatus;
import java.time.Instant;
import java.util.UUID;

public record NotificationBatchResponse(
    UUID id,
    UUID productId,
    String idempotencyKey,
    BatchStatus status,
    int totalCount,
    int acceptedCount,
    int failedCount,
    Instant createdAt,
    Instant updatedAt
) {

    public static NotificationBatchResponse from(NotificationBatch batch) {
        return new NotificationBatchResponse(
            batch.getId(),
            batch.getProduct().getId(),
            batch.getIdempotencyKey(),
            batch.getStatus(),
            batch.getTotalCount(),
            batch.getAcceptedCount(),
            batch.getFailedCount(),
            batch.getCreatedAt(),
            batch.getUpdatedAt()
        );
    }
}
