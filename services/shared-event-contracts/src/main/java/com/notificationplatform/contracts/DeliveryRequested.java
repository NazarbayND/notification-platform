package com.notificationplatform.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

public record DeliveryRequested(
        String eventId,
        String notificationId,
        String deliveryId,
        String tenantId,
        String recipientId,
        String channel,
        String recipientAddress,
        String subject,
        String body,
        int attempt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
        int schemaVersion,
        String originalTopic,
        Integer originalPartition,
        Long originalOffset,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant firstFailureAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lastFailureAt,
        String errorCode,
        String errorMessage) {

    public DeliveryRequested(
            String eventId, String notificationId, String deliveryId, String tenantId, String recipientId,
            String channel, String recipientAddress, String subject, String body, int attempt,
            Instant createdAt, int schemaVersion) {
        this(eventId, notificationId, deliveryId, tenantId, recipientId, channel, recipientAddress, subject, body,
                attempt, createdAt, schemaVersion, null, null, null, null, null, null, null);
    }
}
