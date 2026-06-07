package com.notificationplatform.web.dto;

import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.NotificationPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SendNotificationRequest(
    @NotNull
    UUID productId,

    @NotBlank
    @Size(max = 120)
    String templateKey,

    @NotEmpty
    List<Channel> requestedChannels,

    @NotBlank
    @Size(max = 160)
    String externalUserId,

    @NotBlank
    @Size(max = 160)
    String idempotencyKey,

    @NotBlank
    @Size(max = 80)
    String category,

    NotificationPriority priority,

    Map<String, Object> payload,

    @NotNull
    Map<String, Object> recipient,

    Instant expiresAt
) {
}
