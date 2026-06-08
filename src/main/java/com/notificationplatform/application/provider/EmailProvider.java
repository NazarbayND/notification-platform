package com.notificationplatform.application.provider;

import com.notificationplatform.domain.model.Channel;

public interface EmailProvider extends ProviderAdapter {

    @Override
    default boolean supports(Channel channel) {
        return channel == Channel.EMAIL;
    }
}
