package com.notificationplatform.application.preferences;

import com.notificationplatform.domain.model.Channel;
import java.util.UUID;

public record SetUserPreferenceCommand(
    UUID productId,
    String externalUserId,
    String category,
    Channel channel,
    boolean enabled
) {
}
