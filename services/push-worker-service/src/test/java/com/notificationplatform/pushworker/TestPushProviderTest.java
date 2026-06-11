package com.notificationplatform.pushworker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestPushProviderTest {

    @Test
    void storesSentPushMessages() {
        PushWorkerServiceApplication.TestPushProvider provider =
                new PushWorkerServiceApplication.TestPushProvider(0.0, 0);

        PushWorkerServiceApplication.ProviderResult result = provider.sendPush(
                new PushWorkerServiceApplication.SendPushCommand("device-1", "title", "body", "event-1", "notification-1"));

        assertThat(result.status()).isEqualTo("SENT");
        assertThat(provider.messages()).hasSize(1);
    }

    @Test
    void simulatesRateLimit() {
        PushWorkerServiceApplication.TestPushProvider provider =
                new PushWorkerServiceApplication.TestPushProvider(0.0, 0);

        PushWorkerServiceApplication.ProviderResult result = provider.sendPush(
                new PushWorkerServiceApplication.SendPushCommand("rate-limit-device", "title", "body", "event-1", "notification-1"));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("RATE_LIMIT");
    }
}
