package com.notificationplatform.application.notification;

import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.NotificationPriority;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record BatchNotificationItem(
    String templateKey,
    List<Channel> requestedChannels,
    String externalUserId,
    String idempotencyKey,
    String category,
    NotificationPriority priority,
    Map<String, Object> payload,
    Map<String, Object> recipient,
    Instant expiresAt
) {
}
