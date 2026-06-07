package com.notificationplatform.web.dto;

import com.notificationplatform.domain.entity.NotificationTemplate;
import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.TemplateStatus;
import java.time.Instant;
import java.util.UUID;

public record TemplateResponse(
    UUID id,
    UUID productId,
    String templateKey,
    Channel channel,
    int version,
    String subject,
    String content,
    TemplateStatus status,
    Instant createdAt,
    Instant updatedAt
) {

    public static TemplateResponse from(NotificationTemplate template) {
        return new TemplateResponse(
            template.getId(),
            template.getProduct().getId(),
            template.getTemplateKey(),
            template.getChannel(),
            template.getVersion(),
            template.getSubject(),
            template.getContent(),
            template.getStatus(),
            template.getCreatedAt(),
            template.getUpdatedAt()
        );
    }
}
