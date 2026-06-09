package com.notificationplatform.application.provider;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final EmailProviderFailureMode failureMode;
    private final Duration timeoutDuration;

    @Autowired
    public MailHogEmailProvider(
        JavaMailSender mailSender,
        @Value("${notification.email.from:no-reply@notification-platform.local}") String fromAddress,
        @Value("${notification.email.failure-mode:SUCCESS}") String failureMode,
        @Value("${notification.email.timeout-duration:PT2S}") Duration timeoutDuration
    ) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.failureMode = EmailProviderFailureMode.from(failureMode);
        this.timeoutDuration = timeoutDuration == null ? Duration.ZERO : timeoutDuration;
    }

    MailHogEmailProvider(JavaMailSender mailSender, String fromAddress) {
        this(mailSender, fromAddress, EmailProviderFailureMode.SUCCESS.name(), Duration.ZERO);
    }

    @Override
    public ProviderSendResult send(ProviderSendRequest request) {
        simulateFailureIfConfigured();

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

    private void simulateFailureIfConfigured() {
        switch (failureMode) {
            case SUCCESS -> {
            }
            case TEMPORARY_FAILURE_503 -> throw new ProviderTemporaryException(
                "TEMPORARY_FAILURE_503",
                "Simulated email provider 503"
            );
            case PERMANENT_FAILURE -> throw new ProviderPermanentException(
                "PERMANENT_FAILURE",
                "Simulated permanent email provider failure"
            );
            case TIMEOUT -> {
                sleepForTimeoutSimulation();
                throw new ProviderTemporaryException("TIMEOUT", "Simulated email provider timeout");
            }
        }
    }

    private void sleepForTimeoutSimulation() {
        if (timeoutDuration.isZero() || timeoutDuration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(timeoutDuration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ProviderTemporaryException("TIMEOUT", "Simulated email provider timeout interrupted");
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
