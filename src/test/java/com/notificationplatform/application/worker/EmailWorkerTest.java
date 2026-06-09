package com.notificationplatform.application.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.notificationplatform.application.delivery.NotificationDeliveryService;
import com.notificationplatform.application.delivery.RecordDeliveryFailureCommand;
import com.notificationplatform.application.delivery.RecordDeliverySuccessCommand;
import com.notificationplatform.application.observability.NotificationMetrics;
import com.notificationplatform.application.observability.NotificationTracing;
import com.notificationplatform.application.provider.EmailProvider;
import com.notificationplatform.application.provider.MailHogEmailProvider;
import com.notificationplatform.application.provider.ProviderPermanentException;
import com.notificationplatform.application.provider.ProviderTemporaryException;
import com.notificationplatform.application.queue.DeliveryMessage;
import com.notificationplatform.application.queue.QueuePublisher;
import com.notificationplatform.domain.entity.NotificationDelivery;
import com.notificationplatform.domain.entity.NotificationRequest;
import com.notificationplatform.domain.entity.NotificationTemplate;
import com.notificationplatform.domain.entity.Product;
import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.DeliveryStatus;
import com.notificationplatform.domain.model.NotificationPriority;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class EmailWorkerTest {

    @Mock
    private NotificationDeliveryService deliveryService;

    @Mock
    private QueuePublisher queuePublisher;

    @Mock
    private EmailProvider emailProvider;

    @Mock
    private JavaMailSender mailSender;

    @Test
    void successfulEmailDeliveryThroughRabbitListenerUsesMailHogProviderAndAcknowledgesMessage() throws IOException {
        NotificationDelivery delivery = delivery(DeliveryStatus.SENDING, 1, 3);
        EmailWorker worker = worker(new MailHogEmailProvider(mailSender, "no-reply@example.test", "SUCCESS", Duration.ZERO));
        org.springframework.amqp.core.Message rawMessage = MessageBuilder.withBody(new byte[0]).build();
        rawMessage.getMessageProperties().setDeliveryTag(42L);
        com.rabbitmq.client.Channel rabbitChannel = mock(com.rabbitmq.client.Channel.class);

        when(deliveryService.markSending(delivery.getId(), Duration.ofMinutes(5))).thenReturn(delivery);
        when(deliveryService.getDeliveryForSending(delivery.getId())).thenReturn(delivery);

        worker.consume(message(delivery, 1), rawMessage, rabbitChannel);

        verify(mailSender).send(any(SimpleMailMessage.class));
        verify(deliveryService).recordSuccess(any(RecordDeliverySuccessCommand.class));
        verify(queuePublisher, never()).publishRetry(any(DeliveryMessage.class), any(Duration.class));
        verify(queuePublisher, never()).publishDeadLetter(any(DeliveryMessage.class));
        verify(rabbitChannel).basicAck(42L, false);
    }

    @Test
    void temporaryProviderFailureSchedulesRetryAndPublishesRetryMessage() {
        NotificationDelivery delivery = delivery(DeliveryStatus.SENDING, 1, 3);
        NotificationDelivery retryScheduled = delivery(DeliveryStatus.RETRY_SCHEDULED, 1, 3);
        retryScheduled.setNextAttemptAt(Instant.now().plusSeconds(120));
        EmailWorker worker = worker(emailProvider);

        when(deliveryService.markSending(delivery.getId(), Duration.ofMinutes(5))).thenReturn(delivery);
        when(deliveryService.getDeliveryForSending(delivery.getId())).thenReturn(delivery);
        doThrow(new ProviderTemporaryException("TEMPORARY_FAILURE_503", "Provider unavailable"))
            .when(emailProvider)
            .send(any());
        when(deliveryService.recordFailure(any(RecordDeliveryFailureCommand.class))).thenReturn(retryScheduled);

        worker.processMessage(message(delivery, 1));

        ArgumentCaptor<RecordDeliveryFailureCommand> failureCaptor = ArgumentCaptor.forClass(RecordDeliveryFailureCommand.class);
        verify(deliveryService).recordFailure(failureCaptor.capture());
        verify(queuePublisher).publishRetry(any(DeliveryMessage.class), any(Duration.class));
        verify(queuePublisher, never()).publishDeadLetter(any(DeliveryMessage.class));
    }

    @Test
    void timeoutProviderFailureSchedulesRetry() {
        NotificationDelivery delivery = delivery(DeliveryStatus.SENDING, 1, 3);
        NotificationDelivery retryScheduled = delivery(DeliveryStatus.RETRY_SCHEDULED, 1, 3);
        retryScheduled.setNextAttemptAt(Instant.now().plusSeconds(60));
        EmailWorker worker = worker(new MailHogEmailProvider(mailSender, "no-reply@example.test", "TIMEOUT", Duration.ZERO));

        when(deliveryService.markSending(delivery.getId(), Duration.ofMinutes(5))).thenReturn(delivery);
        when(deliveryService.getDeliveryForSending(delivery.getId())).thenReturn(delivery);
        when(deliveryService.recordFailure(any(RecordDeliveryFailureCommand.class))).thenReturn(retryScheduled);

        worker.processMessage(message(delivery, 1));

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        verify(deliveryService).recordFailure(any(RecordDeliveryFailureCommand.class));
        verify(queuePublisher).publishRetry(any(DeliveryMessage.class), any(Duration.class));
    }

    @Test
    void maxRetriesMovesDeliveryToDeadLetterAndPublishesDlqMessage() {
        NotificationDelivery delivery = delivery(DeliveryStatus.SENDING, 3, 3);
        NotificationDelivery deadLettered = delivery(DeliveryStatus.DEAD_LETTERED, 3, 3);
        EmailWorker worker = worker(emailProvider);

        when(deliveryService.markSending(delivery.getId(), Duration.ofMinutes(5))).thenReturn(delivery);
        when(deliveryService.getDeliveryForSending(delivery.getId())).thenReturn(delivery);
        doThrow(new ProviderTemporaryException("TEMPORARY_FAILURE_503", "Provider unavailable"))
            .when(emailProvider)
            .send(any());
        when(deliveryService.recordFailure(any(RecordDeliveryFailureCommand.class))).thenReturn(deadLettered);

        worker.processMessage(message(delivery, 3));

        verify(deliveryService).recordFailure(any(RecordDeliveryFailureCommand.class));
        verify(queuePublisher, never()).publishRetry(any(DeliveryMessage.class), any(Duration.class));
        verify(queuePublisher).publishDeadLetter(any(DeliveryMessage.class));
    }

    @Test
    void permanentProviderFailureUsesConfiguredTerminalStatus() {
        NotificationDelivery delivery = delivery(DeliveryStatus.SENDING, 1, 3);
        NotificationDelivery failed = delivery(DeliveryStatus.FAILED, 1, 3);
        EmailWorker worker = new EmailWorker(
            deliveryService,
            queuePublisher,
            emailProvider,
            metrics(),
            tracing(),
            Duration.ofMinutes(5),
            Duration.ofMinutes(1),
            "FAILED"
        );

        when(deliveryService.markSending(delivery.getId(), Duration.ofMinutes(5))).thenReturn(delivery);
        when(deliveryService.getDeliveryForSending(delivery.getId())).thenReturn(delivery);
        doThrow(new ProviderPermanentException("PERMANENT_FAILURE", "Rejected"))
            .when(emailProvider)
            .send(any());
        when(deliveryService.recordTerminalFailure(any(RecordDeliveryFailureCommand.class), eq(DeliveryStatus.FAILED)))
            .thenReturn(failed);

        worker.processMessage(message(delivery, 1));

        verify(deliveryService).recordTerminalFailure(any(RecordDeliveryFailureCommand.class), eq(DeliveryStatus.FAILED));
        verify(deliveryService, never()).recordFailure(any(RecordDeliveryFailureCommand.class));
        verify(queuePublisher, never()).publishRetry(any(DeliveryMessage.class), any(Duration.class));
        verify(queuePublisher, never()).publishDeadLetter(any(DeliveryMessage.class));
    }

    @Test
    void duplicateMessageDoesNotSendEmailAgainWhenDeliveryAlreadySent() {
        NotificationDelivery sentDelivery = delivery(DeliveryStatus.SENT, 1, 3);
        EmailWorker worker = worker(emailProvider);

        when(deliveryService.markSending(sentDelivery.getId(), Duration.ofMinutes(5))).thenReturn(sentDelivery);

        worker.processMessage(message(sentDelivery, 1));

        verify(deliveryService, never()).getDeliveryForSending(sentDelivery.getId());
        verify(emailProvider, never()).send(any());
        verify(deliveryService, never()).recordSuccess(any(RecordDeliverySuccessCommand.class));
        verify(deliveryService, never()).recordFailure(any(RecordDeliveryFailureCommand.class));
    }

    private EmailWorker worker(EmailProvider provider) {
        return new EmailWorker(
            deliveryService,
            queuePublisher,
            provider,
            metrics(),
            tracing(),
            Duration.ofMinutes(5),
            Duration.ofMinutes(1),
            "DEAD_LETTERED"
        );
    }

    private static NotificationMetrics metrics() {
        return new NotificationMetrics(new SimpleMeterRegistry());
    }

    private static NotificationTracing tracing() {
        return new NotificationTracing(ObservationRegistry.create());
    }

    private static DeliveryMessage message(NotificationDelivery delivery, int attemptNumber) {
        return new DeliveryMessage(
            delivery.getNotificationRequest().getId(),
            delivery.getId(),
            Channel.EMAIL,
            NotificationPriority.NORMAL,
            attemptNumber
        );
    }

    private static NotificationDelivery delivery(DeliveryStatus status, int attemptCount, int maxAttempts) {
        Product product = new Product("Billing");
        ReflectionTestUtils.setField(product, "id", UUID.randomUUID());

        NotificationTemplate template = new NotificationTemplate(product, "invoice.created", Channel.EMAIL, 1, "Hello");
        template.setSubject("Invoice ready");
        ReflectionTestUtils.setField(template, "id", UUID.randomUUID());

        NotificationRequest request = new NotificationRequest(product, "invoice.created", "user-1", UUID.randomUUID().toString(), "invoice");
        request.setRequestedChannels(java.util.List.of(Channel.EMAIL));
        request.setPriority(NotificationPriority.NORMAL);
        request.setPayload(Map.of("name", "Ada"));
        ReflectionTestUtils.setField(request, "id", UUID.randomUUID());

        NotificationDelivery delivery = new NotificationDelivery(request, template, Channel.EMAIL, "user@example.com");
        delivery.setStatus(status);
        delivery.setAttemptCount(attemptCount);
        delivery.setMaxAttempts(maxAttempts);
        ReflectionTestUtils.setField(delivery, "id", UUID.randomUUID());
        return delivery;
    }
}
