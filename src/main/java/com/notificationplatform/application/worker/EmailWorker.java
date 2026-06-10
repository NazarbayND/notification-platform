package com.notificationplatform.application.worker;

import com.notificationplatform.application.delivery.NotificationDeliveryService;
import com.notificationplatform.application.delivery.RecordDeliveryFailureCommand;
import com.notificationplatform.application.delivery.RecordDeliverySuccessCommand;
import com.notificationplatform.application.observability.NotificationMetrics;
import com.notificationplatform.application.observability.MdcScope;
import com.notificationplatform.application.observability.NotificationTracing;
import com.notificationplatform.application.provider.EmailProvider;
import com.notificationplatform.application.provider.ProviderPermanentException;
import com.notificationplatform.application.provider.ProviderSendRequest;
import com.notificationplatform.application.provider.ProviderSendResult;
import com.notificationplatform.application.provider.ProviderTemporaryException;
import com.notificationplatform.application.queue.DeliveryMessage;
import com.notificationplatform.application.queue.QueuePublisher;
import com.notificationplatform.application.queue.RabbitMqTopology;
import com.notificationplatform.domain.entity.NotificationDelivery;
import com.notificationplatform.domain.model.DeliveryStatus;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EmailWorker {

    private static final Logger log = LoggerFactory.getLogger(EmailWorker.class);

    private final NotificationDeliveryService deliveryService;
    private final QueuePublisher queuePublisher;
    private final EmailProvider emailProvider;
    private final Duration lockDuration;
    private final Duration defaultRetryDelay;
    private final DeliveryStatus permanentFailureStatus;
    private final NotificationMetrics metrics;
    private final NotificationTracing tracing;

    public EmailWorker(
        NotificationDeliveryService deliveryService,
        QueuePublisher queuePublisher,
        EmailProvider emailProvider,
        NotificationMetrics metrics,
        NotificationTracing tracing,
        @Value("${notification.worker.email.lock-duration:PT5M}") Duration lockDuration,
        @Value("${notification.rabbitmq.retry-delay:PT1M}") Duration defaultRetryDelay,
        @Value("${notification.delivery.permanent-failure-status:DEAD_LETTERED}") String permanentFailureStatus
    ) {
        this.deliveryService = deliveryService;
        this.queuePublisher = queuePublisher;
        this.emailProvider = emailProvider;
        this.metrics = metrics;
        this.tracing = tracing;
        this.lockDuration = lockDuration;
        this.defaultRetryDelay = defaultRetryDelay;
        this.permanentFailureStatus = terminalStatus(permanentFailureStatus);
    }

    @RabbitListener(
        queues = {
            RabbitMqTopology.HIGH_EMAIL_QUEUE,
            RabbitMqTopology.NORMAL_EMAIL_QUEUE,
            RabbitMqTopology.LOW_EMAIL_QUEUE
        },
        containerFactory = "manualRabbitListenerContainerFactory"
    )
    public void consume(DeliveryMessage message, Message rawMessage, com.rabbitmq.client.Channel channel) throws IOException {
        long deliveryTag = rawMessage.getMessageProperties().getDeliveryTag();
        try (MdcScope ignored = MdcScope.with(Map.of(
            "notificationRequestId", message.notificationRequestId().toString(),
            "deliveryId", message.deliveryId().toString()
        ))) {
            tracing.observe("rabbitmq.consume", () -> processMessage(message));
            metrics.incrementRabbitMqMessagesConsumed();
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException ex) {
            log.error(
                "Email delivery processing failed before safe state update: deliveryId={}, notificationRequestId={}",
                message.deliveryId(),
                message.notificationRequestId(),
                ex
            );
            channel.basicNack(deliveryTag, false, true);
        }
    }

    void processMessage(DeliveryMessage message) {
        try (MdcScope ignored = MdcScope.with(Map.of(
            "notificationRequestId", message.notificationRequestId().toString(),
            "deliveryId", message.deliveryId().toString()
        ))) {
            metrics.recordDeliveryProcessing(() -> tracing.observe("email.worker.process", () -> processMessageInternal(message)));
        }
    }

    private void processMessageInternal(DeliveryMessage message) {
        log.info(
            "Processing EMAIL delivery message: deliveryId={}, notificationRequestId={}, attemptNumber={}",
            message.deliveryId(),
            message.notificationRequestId(),
            message.attemptNumber()
        );

        NotificationDelivery claimedDelivery = deliveryService.markSending(message.deliveryId(), lockDuration);
        if (claimedDelivery.getStatus() != DeliveryStatus.SENDING) {
            log.info(
                "Skipping EMAIL delivery message because delivery is not sendable: deliveryId={}, notificationRequestId={}, status={}",
                message.deliveryId(),
                message.notificationRequestId(),
                claimedDelivery.getStatus()
            );
            return;
        }

        NotificationDelivery delivery = deliveryService.getDeliveryForSending(message.deliveryId());
        ProviderSendResult result;
        try {
            result = metrics.recordEmailProviderSend(() -> tracing.observe("email.provider.send", () -> emailProvider.send(new ProviderSendRequest(
                delivery.getId(),
                delivery.getChannel(),
                delivery.getDestination(),
                delivery.getTemplate().getSubject(),
                delivery.getTemplate().getContent(),
                delivery.getNotificationRequest().getPayload()
            ))));
            metrics.incrementEmailProviderSendSuccess();
        } catch (ProviderTemporaryException ex) {
            metrics.incrementEmailProviderSendFailure();
            recordFailureAndPublishFollowUp(delivery, ex.getErrorCode(), ex.getMessage());
            return;
        } catch (ProviderPermanentException ex) {
            metrics.incrementEmailProviderSendFailure();
            recordPermanentFailureAndPublishFollowUp(delivery, ex.getErrorCode(), ex.getMessage());
            return;
        } catch (RuntimeException ex) {
            metrics.incrementEmailProviderSendFailure();
            recordFailureAndPublishFollowUp(delivery, "PROVIDER_ERROR", ex.getMessage());
            return;
        }

        deliveryService.recordSuccess(new RecordDeliverySuccessCommand(
            delivery.getId(),
            result.provider(),
            result.providerMessageId(),
            result.responsePayload()
        ));
    }

    private void recordFailureAndPublishFollowUp(NotificationDelivery delivery, String errorCode, String errorMessage) {
        NotificationDelivery failedDelivery = deliveryService.recordFailure(new RecordDeliveryFailureCommand(
            delivery.getId(),
            errorCode,
            errorMessage == null ? "Provider send failed" : errorMessage
        ));

        if (failedDelivery.getStatus() == DeliveryStatus.RETRY_SCHEDULED) {
            publishRetry(failedDelivery);
        } else if (failedDelivery.getStatus() == DeliveryStatus.DEAD_LETTERED || failedDelivery.getStatus() == DeliveryStatus.DLQ) {
            publishDeadLetter(failedDelivery);
        }
    }

    private void recordPermanentFailureAndPublishFollowUp(NotificationDelivery delivery, String errorCode, String errorMessage) {
        NotificationDelivery failedDelivery = deliveryService.recordTerminalFailure(new RecordDeliveryFailureCommand(
            delivery.getId(),
            errorCode,
            errorMessage == null ? "Permanent provider send failure" : errorMessage
        ), permanentFailureStatus);

        if (failedDelivery.getStatus() == DeliveryStatus.DEAD_LETTERED || failedDelivery.getStatus() == DeliveryStatus.DLQ) {
            publishDeadLetter(failedDelivery);
        }
    }

    private void publishRetry(NotificationDelivery delivery) {
        DeliveryMessage retryMessage = toMessage(delivery, delivery.getAttemptCount() + 1);
        Duration delay = retryDelay(delivery);
        try {
            queuePublisher.publishRetry(retryMessage, delay);
            log.info(
                "Published EMAIL delivery retry: deliveryId={}, notificationRequestId={}, delay={}",
                delivery.getId(),
                delivery.getNotificationRequest().getId(),
                delay
            );
        } catch (RuntimeException ex) {
            log.warn(
                "Could not publish EMAIL retry message; retry scheduler will re-enqueue when ready: deliveryId={}, notificationRequestId={}",
                delivery.getId(),
                delivery.getNotificationRequest().getId(),
                ex
            );
        }
    }

    private void publishDeadLetter(NotificationDelivery delivery) {
        try {
            queuePublisher.publishDeadLetter(toMessage(delivery, delivery.getAttemptCount()));
            log.info(
                "Published EMAIL delivery dead-letter message: deliveryId={}, notificationRequestId={}",
                delivery.getId(),
                delivery.getNotificationRequest().getId()
            );
        } catch (RuntimeException ex) {
            log.warn(
                "Could not publish EMAIL dead-letter message after terminal state update: deliveryId={}, notificationRequestId={}",
                delivery.getId(),
                delivery.getNotificationRequest().getId(),
                ex
            );
        }
    }

    private DeliveryMessage toMessage(NotificationDelivery delivery, int attemptNumber) {
        return new DeliveryMessage(
            delivery.getNotificationRequest().getId(),
            delivery.getId(),
            delivery.getChannel(),
            delivery.getNotificationRequest().getPriority(),
            attemptNumber
        );
    }

    private Duration retryDelay(NotificationDelivery delivery) {
        if (delivery.getNextAttemptAt() == null) {
            return defaultRetryDelay;
        }
        Duration delay = Duration.between(Instant.now(), delivery.getNextAttemptAt());
        return delay.isNegative() || delay.isZero() ? defaultRetryDelay : delay;
    }

    private static DeliveryStatus terminalStatus(String value) {
        DeliveryStatus status = DeliveryStatus.valueOf(value == null || value.isBlank()
            ? DeliveryStatus.DEAD_LETTERED.name()
            : value.trim().toUpperCase());
        if (status != DeliveryStatus.FAILED && status != DeliveryStatus.DEAD_LETTERED && status != DeliveryStatus.DLQ) {
            throw new IllegalArgumentException("Permanent failure status must be FAILED, DEAD_LETTERED, or DLQ");
        }
        return status;
    }
}
