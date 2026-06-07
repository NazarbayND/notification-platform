package com.notificationplatform.application.delivery;

import java.util.Map;
import java.util.UUID;

public record RecordDeliverySuccessCommand(
    UUID deliveryId,
    String provider,
    String providerMessageId,
    Map<String, Object> providerResponse
) {
}
