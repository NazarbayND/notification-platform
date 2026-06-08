package com.notificationplatform.application.queue;

import com.notificationplatform.domain.model.NotificationPriority;
import java.time.Duration;

public interface QueuePublisher {

    void publish(NotificationPriority priority, DeliveryMessage message);

    void publishRetry(DeliveryMessage message, Duration delay);

    void publishDeadLetter(DeliveryMessage message);
}
