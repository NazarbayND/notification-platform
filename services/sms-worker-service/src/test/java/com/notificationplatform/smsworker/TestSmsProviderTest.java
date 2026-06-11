package com.notificationplatform.smsworker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestSmsProviderTest {

    @Test
    void storesSentSmsMessages() {
        SmsWorkerServiceApplication.TestSmsProvider provider =
                new SmsWorkerServiceApplication.TestSmsProvider(0.0, 0);

        SmsWorkerServiceApplication.ProviderResult result = provider.sendSms(
                new SmsWorkerServiceApplication.SendSmsCommand("+15551234567", "hello", "event-1", "notification-1"));

        assertThat(result.status()).isEqualTo("SENT");
        assertThat(provider.messages()).hasSize(1);
    }

    @Test
    void simulatesProviderFailureByRecipient() {
        SmsWorkerServiceApplication.TestSmsProvider provider =
                new SmsWorkerServiceApplication.TestSmsProvider(0.0, 0);

        SmsWorkerServiceApplication.ProviderResult result = provider.sendSms(
                new SmsWorkerServiceApplication.SendSmsCommand("fail-user", "hello", "event-1", "notification-1"));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("PROVIDER_FAILURE");
    }
}
