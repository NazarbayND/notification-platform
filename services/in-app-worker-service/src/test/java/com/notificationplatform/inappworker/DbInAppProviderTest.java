package com.notificationplatform.inappworker;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

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

    @Test
    void consumerRecordsMetricsAndClearsCorrelationContext() {
        InAppWorkerServiceApplication.DbInAppProvider provider =
                new InAppWorkerServiceApplication.DbInAppProvider(0.0, 0);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InAppWorkerServiceApplication.InAppDeliveryConsumer consumer =
                new InAppWorkerServiceApplication.InAppDeliveryConsumer(provider, registry);
        InAppWorkerServiceApplication.DeliveryJob job = new InAppWorkerServiceApplication.DeliveryJob(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "IN_APP",
                "user-1",
                "title",
                "body",
                "NORMAL",
                "corr-from-job");

        consumer.consume(job, "corr-from-header");

        assertThat(registry.counter("worker_messages_consumed_total", "service", "in-app-worker-service", "channel", "IN_APP").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("worker_messages_processed_total", "service", "in-app-worker-service", "channel", "IN_APP", "status", "SENT").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("delivery_attempt_total", "channel", "IN_APP", "provider", "db-in-app", "status", "SENT").count())
                .isEqualTo(1.0);
        assertThat(MDC.get("correlationId")).isNull();
    }
}
