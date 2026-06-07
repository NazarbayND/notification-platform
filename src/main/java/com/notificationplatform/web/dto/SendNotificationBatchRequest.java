package com.notificationplatform.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record SendNotificationBatchRequest(
    @NotNull
    UUID productId,

    @NotBlank
    @Size(max = 160)
    String idempotencyKey,

    @Valid
    @NotEmpty
    List<BatchNotificationItemRequest> items
) {
}
