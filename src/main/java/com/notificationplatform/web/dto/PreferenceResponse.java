package com.notificationplatform.web.dto;

import com.notificationplatform.domain.entity.UserNotificationPreference;
import com.notificationplatform.domain.model.Channel;
import java.time.Instant;
import java.util.UUID;

public record PreferenceResponse(
    UUID id,
    UUID productId,
    String externalUserId,
    String category,
    Channel channel,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt
) {

    public static PreferenceResponse from(UserNotificationPreference preference) {
        return new PreferenceResponse(
            preference.getId(),
            preference.getProduct().getId(),
            preference.getExternalUserId(),
            preference.getCategory(),
            preference.getChannel(),
            preference.isEnabled(),
            preference.getCreatedAt(),
            preference.getUpdatedAt()
        );
    }
}
