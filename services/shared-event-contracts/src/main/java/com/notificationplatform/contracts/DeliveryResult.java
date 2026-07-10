package com.notificationplatform.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

public record DeliveryResult(
        String eventId,
        String notificationId,
        String deliveryId,
        String tenantId,
        String channel,
        String status,
        int attempt,
        String providerMessageId,
        String errorCode,
        String errorMessage,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
        int schemaVersion) {
}
