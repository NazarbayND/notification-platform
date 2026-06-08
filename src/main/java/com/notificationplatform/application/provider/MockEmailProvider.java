package com.notificationplatform.application.provider;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "notification.email.provider", havingValue = "mock", matchIfMissing = true)
public class MockEmailProvider implements EmailProvider {

    private static final String PROVIDER_NAME = "mock-email";

    @Override
    public ProviderSendResult send(ProviderSendRequest request) {
        return new ProviderSendResult(
            PROVIDER_NAME,
            PROVIDER_NAME + "-" + request.deliveryId(),
            Map.of(
                "mock", true,
                "destination", request.destination()
            )
        );
    }
}
