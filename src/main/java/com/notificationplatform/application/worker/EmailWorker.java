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
import com.notificationplatform.application.queue.DeliveryQueueConsumer;
import com.notificationplatform.domain.entity.NotificationDelivery;
import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.DeliveryStatus;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EmailWorker {

    private final DeliveryQueueConsumer queueConsumer;
    private final NotificationDeliveryService deliveryService;
    private final ProviderAdapter emailProvider;
    private final int batchSize;
    private final Duration lockDuration;

    public EmailWorker(
        DeliveryQueueConsumer queueConsumer,
        NotificationDeliveryService deliveryService,
        List<ProviderAdapter> providerAdapters,
        @Value("${notification.worker.email.batch-size:20}") int batchSize,
        @Value("${notification.worker.email.lock-duration:PT5M}") Duration lockDuration
    ) {
        this.queueConsumer = queueConsumer;
        this.deliveryService = deliveryService;
        this.emailProvider = providerAdapters.stream()
            .filter(adapter -> adapter.supports(Channel.EMAIL))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No EMAIL provider adapter configured"));
        this.batchSize = batchSize;
        this.lockDuration = lockDuration;
    }

    @Scheduled(fixedDelayString = "${notification.worker.email.fixed-delay:PT2S}")
    public void processAvailableMessages() {
        int processed = 0;
        while (processed < batchSize) {
            DeliveryMessage message = queueConsumer.poll(Channel.EMAIL).orElse(null);
            if (message == null) {
                return;
            }
            processMessage(message);
            processed++;
        }
    }

    void processMessage(DeliveryMessage message) {
        NotificationDelivery claimedDelivery = deliveryService.markSending(message.deliveryId(), lockDuration);
        if (claimedDelivery.getStatus() != DeliveryStatus.SENDING) {
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
            recordFailure(delivery, ex.getErrorCode(), ex.getMessage());
        } catch (ProviderPermanentException ex) {
            recordFailure(delivery, ex.getErrorCode(), ex.getMessage());
        } catch (RuntimeException ex) {
            recordFailure(delivery, "PROVIDER_ERROR", ex.getMessage());
        }
    }

    private void recordFailure(NotificationDelivery delivery, String errorCode, String errorMessage) {
        deliveryService.recordFailure(new RecordDeliveryFailureCommand(
            delivery.getId(),
            errorCode,
            errorMessage == null ? "Provider send failed" : errorMessage
        ));
    }
}
