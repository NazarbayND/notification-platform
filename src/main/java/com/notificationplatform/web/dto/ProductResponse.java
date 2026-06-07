package com.notificationplatform.web.dto;

import com.notificationplatform.domain.entity.Product;
import com.notificationplatform.domain.model.ProductStatus;
import java.time.Instant;
import java.util.UUID;

public record ProductResponse(
    UUID id,
    String name,
    ProductStatus status,
    Instant createdAt,
    Instant updatedAt
) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
            product.getId(),
            product.getName(),
            product.getStatus(),
            product.getCreatedAt(),
            product.getUpdatedAt()
        );
    }
}
