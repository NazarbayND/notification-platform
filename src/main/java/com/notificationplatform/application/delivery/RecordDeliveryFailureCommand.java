package com.notificationplatform.application.delivery;

import java.util.UUID;

public record RecordDeliveryFailureCommand(
    UUID deliveryId,
    String errorCode,
    String errorMessage
) {
}
