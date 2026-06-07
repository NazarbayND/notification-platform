package com.notificationplatform.domain.repository;

import com.notificationplatform.domain.entity.UserNotificationPreference;
import com.notificationplatform.domain.model.Channel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserNotificationPreferenceRepository extends JpaRepository<UserNotificationPreference, UUID> {

    List<UserNotificationPreference> findByProduct_IdAndExternalUserIdOrderByCategoryAscChannelAsc(
        UUID productId,
        String externalUserId
    );

    Optional<UserNotificationPreference> findByProduct_IdAndExternalUserIdAndCategoryAndChannel(
        UUID productId,
        String externalUserId,
        String category,
        Channel channel
    );
}
