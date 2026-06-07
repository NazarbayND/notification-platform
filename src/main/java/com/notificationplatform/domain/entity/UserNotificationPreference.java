package com.notificationplatform.domain.entity;

import com.notificationplatform.domain.common.BaseEntity;
import com.notificationplatform.domain.model.Channel;
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

@Entity
@Table(
    name = "user_notification_preferences",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_user_notification_preferences",
            columnNames = {"product_id", "external_user_id", "category", "channel"}
        )
    },
    indexes = {
        @Index(name = "idx_user_notification_preferences_user", columnList = "product_id,external_user_id")
    }
)
public class UserNotificationPreference extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @NotBlank
    @Size(max = 160)
    @Column(name = "external_user_id", nullable = false, length = 160)
    private String externalUserId;

    @NotBlank
    @Size(max = 80)
    @Column(name = "category", nullable = false, length = 80)
    private String category;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 32)
    private Channel channel;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    protected UserNotificationPreference() {
    }

    public UserNotificationPreference(Product product, String externalUserId, String category, Channel channel, boolean enabled) {
        this.product = product;
        this.externalUserId = externalUserId;
        this.category = category;
        this.channel = channel;
        this.enabled = enabled;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public String getExternalUserId() {
        return externalUserId;
    }

    public void setExternalUserId(String externalUserId) {
        this.externalUserId = externalUserId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
