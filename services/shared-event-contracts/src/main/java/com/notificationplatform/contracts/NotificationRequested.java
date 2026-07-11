package com.notificationplatform.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record NotificationRequested(
        String eventId,
        String notificationId,
        String requestId,
        String tenantId,
        String productId,
        String idempotencyKey,
        String templateId,
        Recipient recipient,
        List<String> requestedChannels,
        Map<String, Object> variables,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant requestedAt,
        int schemaVersion) {

    public NotificationRequested {
        productId = productId == null || productId.isBlank() ? tenantId : productId;
        requestedChannels = requestedChannels == null ? List.of() : List.copyOf(requestedChannels);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }

    public record Recipient(String userId, String email, String phone, String pushToken, String webhookUrl) {
    }
}
