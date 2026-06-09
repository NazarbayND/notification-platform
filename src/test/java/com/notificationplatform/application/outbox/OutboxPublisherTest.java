package com.notificationplatform.application.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.notificationplatform.application.observability.NotificationMetrics;
import com.notificationplatform.application.observability.NotificationTracing;
import com.notificationplatform.application.queue.DeliveryMessage;
import com.notificationplatform.application.queue.QueuePublisher;
import com.notificationplatform.domain.entity.NotificationDelivery;
import com.notificationplatform.domain.entity.NotificationRequest;
import com.notificationplatform.domain.entity.NotificationTemplate;
import com.notificationplatform.domain.entity.OutboxEvent;
import com.notificationplatform.domain.entity.Product;
import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.NotificationPriority;
import com.notificationplatform.domain.model.OutboxEventStatus;
import com.notificationplatform.domain.repository.NotificationDeliveryRepository;
import com.notificationplatform.domain.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-06-07T00:00:00Z");

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private NotificationDeliveryRepository deliveryRepository;

    @Mock
    private QueuePublisher queuePublisher;

    @Test
    void publishEventRoutesDeliveryMessageByPriorityAndMarksPublished() {
        NotificationDelivery delivery = delivery();
        OutboxEvent event = event(delivery.getNotificationRequest().getId(), delivery.getId());
        OutboxPublisher publisher = publisher();

        when(outboxEventRepository.findByIdForUpdate(event.getId())).thenReturn(Optional.of(event));
        when(deliveryRepository.findByIdWithRequestAndTemplate(delivery.getId())).thenReturn(Optional.of(delivery));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        publisher.publishEvent(event.getId());

        ArgumentCaptor<DeliveryMessage> messageCaptor = ArgumentCaptor.forClass(DeliveryMessage.class);
        verify(queuePublisher).publish(org.mockito.ArgumentMatchers.eq(NotificationPriority.HIGH), messageCaptor.capture());

        assertThat(messageCaptor.getValue().deliveryId()).isEqualTo(delivery.getId());
        assertThat(messageCaptor.getValue().channel()).isEqualTo(Channel.EMAIL);
        assertThat(messageCaptor.getValue().attemptNumber()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    void publishEventSchedulesRetryWhenQueuePublishFails() {
        NotificationDelivery delivery = delivery();
        OutboxEvent event = event(delivery.getNotificationRequest().getId(), delivery.getId());
        OutboxPublisher publisher = publisher();

        when(outboxEventRepository.findByIdForUpdate(event.getId())).thenReturn(Optional.of(event));
        when(deliveryRepository.findByIdWithRequestAndTemplate(delivery.getId())).thenReturn(Optional.of(delivery));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new IllegalStateException("queue unavailable"))
            .when(queuePublisher)
            .publish(any(NotificationPriority.class), any(DeliveryMessage.class));

        publisher.publishEvent(event.getId());

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getAvailableAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(event.getLastError()).isEqualTo("queue unavailable");
    }

    @Test
    void publishPendingEventsPublishesLockedBatchAndMarksPublishedInBulk() {
        NotificationDelivery firstDelivery = delivery();
        NotificationDelivery secondDelivery = delivery();
        OutboxEvent firstEvent = event(firstDelivery.getNotificationRequest().getId(), firstDelivery.getId());
        OutboxEvent secondEvent = event(secondDelivery.getNotificationRequest().getId(), secondDelivery.getId());
        OutboxPublisher publisher = publisher();

        when(outboxEventRepository.findReadyPendingEventsForPublishing(NOW, 100))
            .thenReturn(List.of(firstEvent, secondEvent));
        when(deliveryRepository.findAllByIdInWithRequestAndTemplate(any()))
            .thenReturn(List.of(firstDelivery, secondDelivery));
        when(outboxEventRepository.markEventsPublished(
            anyList(),
            eq(OutboxEventStatus.PUBLISHED),
            eq(OutboxEventStatus.PENDING),
            any(Instant.class)
        )).thenReturn(2);

        publisher.publishPendingEvents();

        verify(queuePublisher, times(2)).publish(eq(NotificationPriority.HIGH), any(DeliveryMessage.class));
        verify(outboxEventRepository).markEventsPublished(
            org.mockito.ArgumentMatchers.argThat(ids -> ids.containsAll(List.of(firstEvent.getId(), secondEvent.getId()))),
            eq(OutboxEventStatus.PUBLISHED),
            eq(OutboxEventStatus.PENDING),
            any(Instant.class)
        );
    }

    private OutboxPublisher publisher() {
        return new OutboxPublisher(
            outboxEventRepository,
            deliveryRepository,
            queuePublisher,
            new TransactionTemplate(new NoOpTransactionManager()),
            Clock.fixed(NOW, ZoneOffset.UTC),
            100,
            new NotificationMetrics(new SimpleMeterRegistry()),
            new NotificationTracing(ObservationRegistry.create())
        );
    }

    private static OutboxEvent event(UUID notificationRequestId, UUID deliveryId) {
        OutboxEvent event = new OutboxEvent(
            "NOTIFICATION_REQUEST",
            notificationRequestId,
            "NotificationAccepted",
            Map.of(
                "notificationRequestId", notificationRequestId,
                "priority", NotificationPriority.HIGH,
                "deliveryIds", List.of(deliveryId)
            )
        );
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(event, "createdAt", NOW.minusSeconds(5));
        event.setAvailableAt(NOW);
        return event;
    }

    private static NotificationDelivery delivery() {
        Product product = new Product("Billing");
        NotificationTemplate template = new NotificationTemplate(product, "invoice.created", Channel.EMAIL, 1, "Hello");
        ReflectionTestUtils.setField(template, "id", UUID.randomUUID());

        NotificationRequest request = new NotificationRequest(product, "invoice.created", "user-1", "idem-1", "invoice");
        ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
        request.setPriority(NotificationPriority.HIGH);

        NotificationDelivery delivery = new NotificationDelivery(request, template, Channel.EMAIL, "user@example.com");
        ReflectionTestUtils.setField(delivery, "id", UUID.randomUUID());
        return delivery;
    }

    private static class NoOpTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
