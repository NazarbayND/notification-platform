package com.notificationplatform.domain.repository;

import com.notificationplatform.domain.entity.NotificationTemplate;
import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.TemplateStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {

    boolean existsByProduct_IdAndTemplateKeyAndChannelAndVersion(
        UUID productId,
        String templateKey,
        Channel channel,
        int version
    );

    boolean existsByProduct_IdAndTemplateKeyAndChannelAndStatus(
        UUID productId,
        String templateKey,
        Channel channel,
        TemplateStatus status
    );

    List<NotificationTemplate> findByProduct_IdOrderByCreatedAtDesc(UUID productId);

    Optional<NotificationTemplate> findByProduct_IdAndTemplateKeyAndChannelAndStatus(
        UUID productId,
        String templateKey,
        Channel channel,
        TemplateStatus status
    );
}
