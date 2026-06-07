package com.notificationplatform.domain.entity;

import com.notificationplatform.domain.common.BaseEntity;
import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.TemplateStatus;
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
    name = "notification_templates",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_notification_templates_version",
            columnNames = {"product_id", "template_key", "channel", "version"}
        )
    },
    indexes = {
        @Index(name = "idx_notification_templates_product_status", columnList = "product_id,status")
    }
)
public class NotificationTemplate extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @NotBlank
    @Size(max = 120)
    @Column(name = "template_key", nullable = false, length = 120)
    private String templateKey;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 32)
    private Channel channel;

    @Min(1)
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "subject", columnDefinition = "text")
    private String subject;

    @NotBlank
    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TemplateStatus status = TemplateStatus.DRAFT;

    protected NotificationTemplate() {
    }

    public NotificationTemplate(Product product, String templateKey, Channel channel, int version, String content) {
        this.product = product;
        this.templateKey = templateKey;
        this.channel = channel;
        this.version = version;
        this.content = content;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public void setTemplateKey(String templateKey) {
        this.templateKey = templateKey;
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public TemplateStatus getStatus() {
        return status;
    }

    public void setStatus(TemplateStatus status) {
        this.status = status;
    }
}
