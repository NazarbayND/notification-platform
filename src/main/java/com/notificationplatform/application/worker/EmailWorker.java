package com.notificationplatform.application.worker;

import com.notificationplatform.application.delivery.NotificationDeliveryService;
import com.notificationplatform.application.delivery.RecordDeliveryFailureCommand;
import com.notificationplatform.application.delivery.RecordDeliverySuccessCommand;
import com.notificationplatform.application.provider.ProviderAdapter;
import com.notificationplatform.application.provider.ProviderPermanentException;
import com.notificationplatform.application.provider.ProviderSendRequest;
import com.notificationplatform.application.provider.ProviderSendResult;
import com.notificationplatform.application.provider.ProviderTemporaryException;
import com.notificationplatform.application.queue.DeliveryMessage;
import com.notificationplatform.application.queue.QueuePublisher;
import com.notificationplatform.application.queue.RabbitMqTopology;
import com.notificationplatform.domain.entity.NotificationDelivery;
import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.DeliveryStatus;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    private final ProviderAdapter emailProvider;
    private final Duration lockDuration;
    private final Duration defaultRetryDelay;

    public EmailWorker(
        NotificationDeliveryService deliveryService,
        QueuePublisher queuePublisher,
        List<ProviderAdapter> providerAdapters,
        @Value("${notification.worker.email.lock-duration:PT5M}") Duration lockDuration,
        @Value("${notification.rabbitmq.retry-delay:PT1M}") Duration defaultRetryDelay
    ) {
        this.deliveryService = deliveryService;
        this.queuePublisher = queuePublisher;
        this.emailProvider = providerAdapters.stream()
            .filter(adapter -> adapter.supports(Channel.EMAIL))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No EMAIL provider adapter configured"));
        this.lockDuration = lockDuration;
        this.defaultRetryDelay = defaultRetryDelay;
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
        try {
            processMessage(message);
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
        try {
            ProviderSendResult result = emailProvider.send(new ProviderSendRequest(
                delivery.getId(),
                delivery.getChannel(),
                delivery.getDestination(),
                delivery.getTemplate().getSubject(),
                delivery.getTemplate().getContent(),
                delivery.getNotificationRequest().getPayload()
            ));

            deliveryService.recordSuccess(new RecordDeliverySuccessCommand(
                delivery.getId(),
                result.provider(),
                result.providerMessageId(),
                result.responsePayload()
            ));
        } catch (ProviderTemporaryException ex) {
            recordFailureAndPublishFollowUp(delivery, ex.getErrorCode(), ex.getMessage());
        } catch (ProviderPermanentException ex) {
            recordFailureAndPublishFollowUp(delivery, ex.getErrorCode(), ex.getMessage());
        } catch (RuntimeException ex) {
            recordFailureAndPublishFollowUp(delivery, "PROVIDER_ERROR", ex.getMessage());
        }
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
}
