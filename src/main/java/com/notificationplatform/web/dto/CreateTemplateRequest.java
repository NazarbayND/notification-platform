package com.notificationplatform.web.dto;

import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.TemplateStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateTemplateRequest(
    @NotNull
    UUID productId,

    @NotBlank
    @Size(max = 120)
    String templateKey,

    @NotNull
    Channel channel,

    @Min(1)
    int version,

    String subject,

    @NotBlank
    String content,

    TemplateStatus status
) {
}
