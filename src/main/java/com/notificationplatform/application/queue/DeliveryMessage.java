package com.notificationplatform.application.queue;

import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.NotificationPriority;
import java.util.UUID;

public record DeliveryMessage(
    UUID notificationRequestId,
    UUID deliveryId,
    Channel channel,
    NotificationPriority priority,
    int attemptNumber
) {
}
