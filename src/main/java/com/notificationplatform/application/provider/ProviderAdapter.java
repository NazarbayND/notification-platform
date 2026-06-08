package com.notificationplatform.application.provider;

import com.notificationplatform.domain.model.Channel;

public interface ProviderAdapter {

    boolean supports(Channel channel);

    ProviderSendResult send(ProviderSendRequest request);
}
