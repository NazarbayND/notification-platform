package com.notificationplatform.application.notification;

import java.util.List;
import java.util.UUID;

public record CreateNotificationBatchCommand(
    UUID productId,
    String idempotencyKey,
    List<BatchNotificationItem> items
) {
}
