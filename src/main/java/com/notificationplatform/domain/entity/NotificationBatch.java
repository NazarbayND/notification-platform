package com.notificationplatform.domain.entity;

import com.notificationplatform.domain.common.BaseEntity;
import com.notificationplatform.domain.model.BatchStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Entity
@Table(
    name = "notification_batches",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_notification_batches_idempotency", columnNames = {"product_id", "idempotency_key"})
    },
    indexes = {
        @Index(name = "idx_notification_batches_product_created", columnList = "product_id,created_at")
    }
)
public class NotificationBatch extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @NotBlank
    @Size(max = 160)
    @Column(name = "idempotency_key", nullable = false, length = 160)
    private String idempotencyKey;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private BatchStatus status = BatchStatus.ACCEPTED;

    @Min(0)
    @Column(name = "total_count", nullable = false)
    private int totalCount;

    @Min(0)
    @Column(name = "accepted_count", nullable = false)
    private int acceptedCount;

    @Min(0)
    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    protected NotificationBatch() {
    }

    public NotificationBatch(Product product, String idempotencyKey, int totalCount) {
        this.product = product;
        this.idempotencyKey = idempotencyKey;
        this.totalCount = totalCount;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public BatchStatus getStatus() {
        return status;
    }

    public void setStatus(BatchStatus status) {
        this.status = status;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
    }

    public int getAcceptedCount() {
        return acceptedCount;
    }

    public void setAcceptedCount(int acceptedCount) {
        this.acceptedCount = acceptedCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(int failedCount) {
        this.failedCount = failedCount;
    }
}
