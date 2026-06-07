package com.notificationplatform.web.dto;

import com.notificationplatform.domain.model.Channel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record SetPreferenceRequest(
    @NotNull
    UUID productId,

    @NotBlank
    @Size(max = 80)
    String category,

    @NotNull
    Channel channel,

    boolean enabled
) {
}
