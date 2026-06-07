package com.notificationplatform.application.notification;

import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.NotificationPriority;
import java.util.Map;
import java.util.UUID;

public record CreateNotificationCommand(
    UUID productId,
    String templateKey,
    Channel channel,
    String externalUserId,
    String idempotencyKey,
    String category,
    NotificationPriority priority,
    Map<String, Object> payload,
    Map<String, Object> recipient
) {
}
