package com.notificationplatform.web.dto;

import com.notificationplatform.domain.entity.NotificationDelivery;
import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.DeliveryStatus;
import java.time.Instant;
import java.util.UUID;

public record DeliveryResponse(
    UUID id,
    UUID notificationRequestId,
    UUID templateId,
    Channel channel,
    DeliveryStatus status,
    String provider,
    String destination,
    int attemptCount,
    int maxAttempts,
    Instant nextAttemptAt,
    String lastErrorMessage,
    Instant createdAt
) {

    public static DeliveryResponse from(NotificationDelivery delivery) {
        return new DeliveryResponse(
            delivery.getId(),
            delivery.getNotificationRequest().getId(),
            delivery.getTemplate().getId(),
            delivery.getChannel(),
            delivery.getStatus(),
            delivery.getProvider(),
            delivery.getDestination(),
            delivery.getAttemptCount(),
            delivery.getMaxAttempts(),
            delivery.getNextAttemptAt(),
            delivery.getLastErrorMessage(),
            delivery.getCreatedAt()
        );
    }
}
