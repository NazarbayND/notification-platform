package com.notificationplatform.application.management;

import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.TemplateStatus;
import java.util.UUID;

public record CreateTemplateCommand(
    UUID productId,
    String templateKey,
    Channel channel,
    int version,
    String subject,
    String content,
    TemplateStatus status
) {
}
