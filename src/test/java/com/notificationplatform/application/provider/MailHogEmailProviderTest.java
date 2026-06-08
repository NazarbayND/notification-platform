package com.notificationplatform.application.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.notificationplatform.domain.model.Channel;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class MailHogEmailProviderTest {

    @Mock
    private JavaMailSender mailSender;

    @Test
    void sendUsesJavaMailSenderAndReturnsProviderResult() {
        MailHogEmailProvider provider = new MailHogEmailProvider(mailSender, "no-reply@example.test");
        UUID deliveryId = UUID.randomUUID();

        ProviderSendResult result = provider.send(new ProviderSendRequest(
            deliveryId,
            Channel.EMAIL,
            "user@example.com",
            "Invoice ready",
            "Hello from the notification platform.",
            Map.of("name", "Ada")
        ));

        ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());

        SimpleMailMessage message = messageCaptor.getValue();
        assertThat(message.getFrom()).isEqualTo("no-reply@example.test");
        assertThat(message.getTo()).containsExactly("user@example.com");
        assertThat(message.getSubject()).isEqualTo("Invoice ready");
        assertThat(message.getText()).isEqualTo("Hello from the notification platform.");
        assertThat(result.provider()).isEqualTo("mailhog-smtp");
        assertThat(result.providerMessageId()).isEqualTo("mailhog-smtp-" + deliveryId);
    }
}
