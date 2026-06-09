package com.notificationplatform.application.preferences;

import com.notificationplatform.application.common.ResourceNotFoundException;
import com.notificationplatform.application.cache.NotificationCacheService;
import com.notificationplatform.domain.entity.Product;
import com.notificationplatform.domain.entity.UserNotificationPreference;
import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.repository.ProductRepository;
import com.notificationplatform.domain.repository.UserNotificationPreferenceRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserPreferenceService {

    private final ProductRepository productRepository;
    private final UserNotificationPreferenceRepository preferenceRepository;
    private final NotificationCacheService cacheService;

    public UserPreferenceService(
        ProductRepository productRepository,
        UserNotificationPreferenceRepository preferenceRepository,
        NotificationCacheService cacheService
    ) {
        this.productRepository = productRepository;
        this.preferenceRepository = preferenceRepository;
        this.cacheService = cacheService;
    }

    @Transactional
    public UserNotificationPreference setPreference(SetUserPreferenceCommand command) {
        Objects.requireNonNull(command, "Set user preference command is required");
        Objects.requireNonNull(command.productId(), "Product id is required");
        Channel channel = Objects.requireNonNull(command.channel(), "Preference channel is required");
        String externalUserId = normalizeRequired(command.externalUserId(), "External user id is required");
        String category = normalizeRequired(command.category(), "Preference category is required");

        UserNotificationPreference preference = preferenceRepository.findByProduct_IdAndExternalUserIdAndCategoryAndChannel(
            command.productId(),
            externalUserId,
            category,
            channel
        ).map(existing -> {
            existing.setEnabled(command.enabled());
            return preferenceRepository.save(existing);
        }).orElseGet(() -> {
            Product product = productRepository.findById(command.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + command.productId()));
            return preferenceRepository.save(new UserNotificationPreference(
                product,
                externalUserId,
                category,
                channel,
                command.enabled()
            ));
        });
        cacheService.evictPreference(command.productId(), externalUserId, category, channel);
        return preference;
    }

    @Transactional(readOnly = true)
    public List<UserNotificationPreference> listPreferences(UUID productId, String externalUserId) {
        Objects.requireNonNull(productId, "Product id is required");
        String normalizedExternalUserId = normalizeRequired(externalUserId, "External user id is required");
        return preferenceRepository.findByProduct_IdAndExternalUserIdOrderByCategoryAscChannelAsc(
            productId,
            normalizedExternalUserId
        );
    }

    @Transactional(readOnly = true)
    public boolean isChannelEnabled(UUID productId, String externalUserId, String category, Channel channel) {
        Objects.requireNonNull(productId, "Product id is required");
        Objects.requireNonNull(channel, "Preference channel is required");
        String normalizedExternalUserId = normalizeRequired(externalUserId, "External user id is required");
        String normalizedCategory = normalizeRequired(category, "Preference category is required");

        return cacheService.getPreferenceEnabled(productId, normalizedExternalUserId, normalizedCategory, channel)
            .orElseGet(() -> {
                boolean enabled = preferenceRepository.findByProduct_IdAndExternalUserIdAndCategoryAndChannel(
            productId,
            normalizedExternalUserId,
            normalizedCategory,
            channel
                ).map(UserNotificationPreference::isEnabled).orElse(true);
                cacheService.putPreferenceEnabled(productId, normalizedExternalUserId, normalizedCategory, channel, enabled);
                return enabled;
            });
    }

    private static String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
