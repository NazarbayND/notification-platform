package com.notificationplatform.application.provider;

import java.util.Map;

public record ProviderSendResult(
    String provider,
    String providerMessageId,
    Map<String, Object> responsePayload
) {
}
