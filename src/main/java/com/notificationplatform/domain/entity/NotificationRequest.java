package com.notificationplatform.domain.entity;

import com.notificationplatform.domain.common.BaseEntity;
import com.notificationplatform.domain.model.NotificationPriority;
import com.notificationplatform.domain.model.NotificationRequestStatus;
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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "notification_requests",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_notification_requests_idempotency", columnNames = {"product_id", "idempotency_key"})
    },
    indexes = {
        @Index(name = "idx_notification_requests_user_created", columnList = "product_id,external_user_id,created_at"),
        @Index(name = "idx_notification_requests_status_created", columnList = "status,created_at"),
        @Index(name = "idx_notification_requests_batch", columnList = "batch_id")
    }
)
public class NotificationRequest extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private NotificationBatch batch;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    private NotificationTemplate template;

    @NotBlank
    @Size(max = 160)
    @Column(name = "external_user_id", nullable = false, length = 160)
    private String externalUserId;

    @NotBlank
    @Size(max = 160)
    @Column(name = "idempotency_key", nullable = false, length = 160)
    private String idempotencyKey;

    @NotBlank
    @Size(max = 80)
    @Column(name = "category", nullable = false, length = 80)
    private String category;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 32)
    private NotificationPriority priority = NotificationPriority.NORMAL;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = new LinkedHashMap<>();

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recipient", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> recipient = new LinkedHashMap<>();

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private NotificationRequestStatus status = NotificationRequestStatus.ACCEPTED;

    protected NotificationRequest() {
    }

    public NotificationRequest(
        Product product,
        NotificationTemplate template,
        String externalUserId,
        String idempotencyKey,
        String category
    ) {
        this.product = product;
        this.template = template;
        this.externalUserId = externalUserId;
        this.idempotencyKey = idempotencyKey;
        this.category = category;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public NotificationBatch getBatch() {
        return batch;
    }

    public void setBatch(NotificationBatch batch) {
        this.batch = batch;
    }

    public NotificationTemplate getTemplate() {
        return template;
    }

    public void setTemplate(NotificationTemplate template) {
        this.template = template;
    }

    public String getExternalUserId() {
        return externalUserId;
    }

    public void setExternalUserId(String externalUserId) {
        this.externalUserId = externalUserId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public NotificationPriority getPriority() {
        return priority;
    }

    public void setPriority(NotificationPriority priority) {
        this.priority = priority;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    public Map<String, Object> getRecipient() {
        return recipient;
    }

    public void setRecipient(Map<String, Object> recipient) {
        this.recipient = recipient;
    }

    public NotificationRequestStatus getStatus() {
        return status;
    }

    public void setStatus(NotificationRequestStatus status) {
        this.status = status;
    }
}
