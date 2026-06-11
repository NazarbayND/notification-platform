package com.notificationplatform.inappworker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DbInAppProviderTest {

    @Test
    void storesAndMarksInAppNotificationsRead() {
        InAppWorkerServiceApplication.DbInAppProvider provider =
                new InAppWorkerServiceApplication.DbInAppProvider(0.0, 0);

        provider.createInAppNotification(new InAppWorkerServiceApplication.CreateInAppNotificationCommand(
                "user-1", "title", "body", "event-1", "notification-1"));

        InAppWorkerServiceApplication.InAppNotification notification = provider.forUser("user-1").getFirst();
        InAppWorkerServiceApplication.InAppNotification read = provider.markRead("user-1", notification.id());

        assertThat(read.read()).isTrue();
        assertThat(read.readAt()).isNotNull();
    }

    @Test
    void ignoresReadForAnotherUser() {
        InAppWorkerServiceApplication.DbInAppProvider provider =
                new InAppWorkerServiceApplication.DbInAppProvider(0.0, 0);

        assertThat(provider.markRead("user-1", UUID.randomUUID())).isNull();
    }
}
