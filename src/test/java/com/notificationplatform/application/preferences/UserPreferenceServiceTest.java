package com.notificationplatform.application.preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.notificationplatform.application.cache.NotificationCacheService;
import com.notificationplatform.domain.entity.Product;
import com.notificationplatform.domain.entity.UserNotificationPreference;
import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.repository.ProductRepository;
import com.notificationplatform.domain.repository.UserNotificationPreferenceRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserPreferenceServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private UserNotificationPreferenceRepository preferenceRepository;

    @Mock
    private NotificationCacheService cacheService;

    @InjectMocks
    private UserPreferenceService service;

    @Test
    void setPreferenceUpdatesExistingPreference() {
        UUID productId = UUID.randomUUID();
        UserNotificationPreference preference = new UserNotificationPreference(
            new Product("Billing"),
            "user-1",
            "invoice",
            Channel.EMAIL,
            true
        );

        when(preferenceRepository.findByProduct_IdAndExternalUserIdAndCategoryAndChannel(
            productId,
            "user-1",
            "invoice",
            Channel.EMAIL
        )).thenReturn(Optional.of(preference));
        when(preferenceRepository.save(preference)).thenReturn(preference);

        UserNotificationPreference updated = service.setPreference(new SetUserPreferenceCommand(
            productId,
            " user-1 ",
            " invoice ",
            Channel.EMAIL,
            false
        ));

        assertThat(updated.isEnabled()).isFalse();
        verify(preferenceRepository).save(preference);
    }

    @Test
    void setPreferenceCreatesPreferenceWhenMissing() {
        UUID productId = UUID.randomUUID();
        Product product = new Product("Billing");

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(preferenceRepository.save(any(UserNotificationPreference.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserNotificationPreference preference = service.setPreference(new SetUserPreferenceCommand(
            productId,
            "user-1",
            "invoice",
            Channel.SMS,
            true
        ));

        assertThat(preference.getProduct()).isSameAs(product);
        assertThat(preference.getExternalUserId()).isEqualTo("user-1");
        assertThat(preference.getCategory()).isEqualTo("invoice");
        assertThat(preference.getChannel()).isEqualTo(Channel.SMS);
        assertThat(preference.isEnabled()).isTrue();
    }

    @Test
    void isChannelEnabledDefaultsToTrueWhenNoPreferenceExists() {
        UUID productId = UUID.randomUUID();
        when(cacheService.getPreferenceEnabled(productId, "user-1", "invoice", Channel.PUSH)).thenReturn(Optional.empty());

        boolean enabled = service.isChannelEnabled(productId, "user-1", "invoice", Channel.PUSH);

        assertThat(enabled).isTrue();
    }
}
