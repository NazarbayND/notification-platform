package com.notificationplatform.webhookworker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TestWebhookProviderTest {

    @Test
    void recordsSuccessfulWebhookInLocalStore() {
        WebhookWorkerServiceApplication.LocalWebhookStore store =
                new WebhookWorkerServiceApplication.LocalWebhookStore();
        WebhookWorkerServiceApplication.TestWebhookProvider provider =
                new WebhookWorkerServiceApplication.TestWebhookProvider(store, 0.0, 0);

        WebhookWorkerServiceApplication.ProviderResult result = provider.sendWebhook(
                new WebhookWorkerServiceApplication.SendWebhookCommand(
                        "http://webhook-worker-service:8090/webhooks/test",
                        "POST",
                        Map.of("x-test", "true"),
                        "{\"ok\":true}",
                        "event-1",
                        "notification-1"));

        assertThat(result.status()).isEqualTo("SENT");
        assertThat(store.all()).hasSize(1);
    }

    @Test
    void doesNotRecordFailedWebhook() {
        WebhookWorkerServiceApplication.LocalWebhookStore store =
                new WebhookWorkerServiceApplication.LocalWebhookStore();
        WebhookWorkerServiceApplication.TestWebhookProvider provider =
                new WebhookWorkerServiceApplication.TestWebhookProvider(store, 0.0, 0);

        WebhookWorkerServiceApplication.ProviderResult result = provider.sendWebhook(
                new WebhookWorkerServiceApplication.SendWebhookCommand(
                        "http://fail.local/webhooks/test",
                        "POST",
                        Map.of(),
                        "",
                        "event-1",
                        "notification-1"));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(store.all()).isEmpty();
    }
}
