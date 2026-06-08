package com.notificationplatform.application.queue;

import com.notificationplatform.domain.model.NotificationPriority;

public interface QueuePublisher {

    void publish(NotificationPriority priority, DeliveryMessage message);
}
