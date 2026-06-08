package com.notificationplatform.application.provider;

import com.notificationplatform.domain.model.Channel;
import java.util.Map;
import java.util.UUID;

public record ProviderSendRequest(
    UUID deliveryId,
    Channel channel,
    String destination,
    String subject,
    String content,
    Map<String, Object> payload
) {
}
