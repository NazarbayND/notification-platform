package com.notificationplatform.application.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.notificationplatform.domain.entity.NotificationDelivery;
import com.notificationplatform.domain.entity.NotificationRequest;
import com.notificationplatform.domain.entity.NotificationTemplate;
import com.notificationplatform.domain.entity.Product;
import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.DeliveryStatus;
import com.notificationplatform.domain.model.NotificationRequestStatus;
import com.notificationplatform.domain.repository.NotificationDeliveryRepository;
import com.notificationplatform.domain.repository.NotificationRequestRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-07T00:00:00Z");

    @Mock
    private NotificationDeliveryRepository deliveryRepository;

    @Mock
    private NotificationRequestRepository requestRepository;

    @Test
    void recordFailureSchedulesRetryBeforeMaxAttempts() {
        NotificationDelivery delivery = deliveryWithAttemptCount(1, 3);
        NotificationDeliveryService service = service();

        when(deliveryRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));
        when(deliveryRepository.save(any(NotificationDelivery.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(deliveryRepository.findByNotificationRequest_IdOrderByCreatedAtAsc(delivery.getNotificationRequest().getId()))
            .thenReturn(List.of(delivery));

        NotificationDelivery result = service.recordFailure(new RecordDeliveryFailureCommand(
            delivery.getId(),
            "TEMPORARY",
            "Provider timeout"
        ));

        assertThat(result.getStatus()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
        assertThat(result.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(result.getNotificationRequest().getStatus()).isEqualTo(NotificationRequestStatus.DELIVERY_CREATED);
    }

    @Test
    void recordFailureMovesDeliveryToDlqAfterMaxAttempts() {
        NotificationDelivery delivery = deliveryWithAttemptCount(3, 3);
        NotificationDeliveryService service = service();

        when(deliveryRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));
        when(deliveryRepository.save(any(NotificationDelivery.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(deliveryRepository.findByNotificationRequest_IdOrderByCreatedAtAsc(delivery.getNotificationRequest().getId()))
            .thenReturn(List.of(delivery));

        NotificationDelivery result = service.recordFailure(new RecordDeliveryFailureCommand(
            delivery.getId(),
            "PERMANENT",
            "Rejected"
        ));

        assertThat(result.getStatus()).isEqualTo(DeliveryStatus.DLQ);
        assertThat(result.getFailedAt()).isEqualTo(NOW);
        assertThat(result.getNotificationRequest().getStatus()).isEqualTo(NotificationRequestStatus.FAILED);
    }

    private NotificationDeliveryService service() {
        return new NotificationDeliveryService(
            deliveryRepository,
            requestRepository,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static NotificationDelivery deliveryWithAttemptCount(int attemptCount, int maxAttempts) {
        Product product = new Product("Billing");
        NotificationTemplate template = new NotificationTemplate(product, "invoice.created", Channel.EMAIL, 1, "Hello");
        NotificationRequest request = new NotificationRequest(product, template, "user-1", "idem-1", "invoice");
        request.setStatus(NotificationRequestStatus.DELIVERY_CREATED);
        ReflectionTestUtils.setField(request, "id", UUID.randomUUID());

        NotificationDelivery delivery = new NotificationDelivery(request, Channel.EMAIL, "user@example.com");
        ReflectionTestUtils.setField(delivery, "id", UUID.randomUUID());
        delivery.setStatus(DeliveryStatus.PROCESSING);
        delivery.setAttemptCount(attemptCount);
        delivery.setMaxAttempts(maxAttempts);
        return delivery;
    }
}
