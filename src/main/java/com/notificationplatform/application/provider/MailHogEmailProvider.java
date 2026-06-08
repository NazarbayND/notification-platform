package com.notificationplatform.application.provider;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "notification.email.provider", havingValue = "mailhog")
public class MailHogEmailProvider implements EmailProvider {

    private static final String PROVIDER_NAME = "mailhog-smtp";

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public MailHogEmailProvider(
        JavaMailSender mailSender,
        @Value("${notification.email.from:no-reply@notification-platform.local}") String fromAddress
    ) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public ProviderSendResult send(ProviderSendRequest request) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(request.destination());
            message.setSubject(defaultSubject(request.subject()));
            message.setText(defaultContent(request.content()));
            mailSender.send(message);

            return new ProviderSendResult(
                PROVIDER_NAME,
                PROVIDER_NAME + "-" + request.deliveryId(),
                Map.of(
                    "smtp", true,
                    "provider", PROVIDER_NAME,
                    "destination", request.destination()
                )
            );
        } catch (MailException ex) {
            throw new ProviderTemporaryException("SMTP_SEND_FAILED", ex.getMessage());
        }
    }

    private static String defaultSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return "Notification";
        }
        return subject;
    }

    private static String defaultContent(String content) {
        if (content == null || content.isBlank()) {
            return "You have a new notification.";
        }
        return content;
    }
}
