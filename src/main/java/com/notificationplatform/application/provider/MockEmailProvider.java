package com.notificationplatform.application.provider;

import com.notificationplatform.domain.model.Channel;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MockEmailProvider implements ProviderAdapter {

    private static final String PROVIDER_NAME = "mock-email";

    @Override
    public boolean supports(Channel channel) {
        return channel == Channel.EMAIL;
    }

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
