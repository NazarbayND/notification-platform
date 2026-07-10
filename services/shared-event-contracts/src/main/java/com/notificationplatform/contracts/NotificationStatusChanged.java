package com.notificationplatform.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

public record NotificationStatusChanged(
        String eventId,
        String notificationId,
        String tenantId,
        String status,
        String reasonCode,
        String reasonMessage,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
        int schemaVersion) {
}
