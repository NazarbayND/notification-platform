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
        int schemaVersion) {
}
