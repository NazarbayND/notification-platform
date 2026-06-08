package com.notificationplatform.application.worker;

import com.notificationplatform.application.delivery.NotificationDeliveryService;
import com.notificationplatform.application.queue.DeliveryMessage;
import com.notificationplatform.application.queue.QueuePublisher;
import com.notificationplatform.domain.entity.NotificationDelivery;
import com.notificationplatform.domain.model.NotificationPriority;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DeliveryRetryScheduler {

    private final NotificationDeliveryService deliveryService;
    private final QueuePublisher queuePublisher;
    private final int batchSize;

    public DeliveryRetryScheduler(
        NotificationDeliveryService deliveryService,
        QueuePublisher queuePublisher,
        @Value("${notification.delivery.retry-scheduler.batch-size:100}") int batchSize
    ) {
        this.deliveryService = deliveryService;
        this.queuePublisher = queuePublisher;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${notification.delivery.retry-scheduler.fixed-delay:PT10S}")
    public void enqueueReadyRetries() {
        for (NotificationDelivery delivery : deliveryService.findReadyForRetry(batchSize)) {
            NotificationPriority priority = delivery.getNotificationRequest().getPriority();
            queuePublisher.publish(
                priority,
                new DeliveryMessage(
                    delivery.getNotificationRequest().getId(),
                    delivery.getId(),
                    delivery.getChannel(),
                    priority,
                    delivery.getAttemptCount() + 1
                )
            );
        }
    }
}
