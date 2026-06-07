package com.notificationplatform.application.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.notificationplatform.application.preferences.UserPreferenceService;
import com.notificationplatform.domain.entity.NotificationDelivery;
import com.notificationplatform.domain.entity.NotificationRequest;
import com.notificationplatform.domain.entity.NotificationTemplate;
import com.notificationplatform.domain.entity.OutboxEvent;
import com.notificationplatform.domain.entity.Product;
import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.DeliveryStatus;
import com.notificationplatform.domain.model.NotificationPriority;
import com.notificationplatform.domain.model.NotificationRequestStatus;
import com.notificationplatform.domain.model.TemplateStatus;
import com.notificationplatform.domain.repository.NotificationBatchRepository;
import com.notificationplatform.domain.repository.NotificationDeliveryRepository;
import com.notificationplatform.domain.repository.NotificationRequestRepository;
import com.notificationplatform.domain.repository.NotificationTemplateRepository;
import com.notificationplatform.domain.repository.OutboxEventRepository;
import com.notificationplatform.domain.repository.ProductRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class NotificationSubmissionServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private NotificationTemplateRepository templateRepository;

    @Mock
    private NotificationRequestRepository requestRepository;

    @Mock
    private NotificationDeliveryRepository deliveryRepository;

    @Mock
    private NotificationBatchRepository batchRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private UserPreferenceService userPreferenceService;

    @InjectMocks
    private NotificationSubmissionService service;

    @Test
    void createNotificationReturnsExistingRequestForSameIdempotencyKey() {
        UUID productId = UUID.randomUUID();
        NotificationRequest existing = new NotificationRequest(
            new Product("Billing"),
            "invoice.created",
            "user-1",
            "idem-1",
            "invoice"
        );

        when(requestRepository.findByProduct_IdAndIdempotencyKey(productId, "idem-1")).thenReturn(Optional.of(existing));

        NotificationRequest result = service.createNotification(command(productId));

        assertThat(result).isSameAs(existing);
        verify(productRepository, never()).findById(productId);
    }

    @Test
    void createNotificationCreatesDeliveryAndOutboxEventWhenPreferenceAllowsChannel() {
        UUID productId = UUID.randomUUID();
        Product product = new Product("Billing");
        ReflectionTestUtils.setField(product, "id", productId);
        NotificationTemplate template = template(product);

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(templateRepository.findByProduct_IdAndTemplateKeyAndChannelAndStatus(
            productId,
            "invoice.created",
            Channel.EMAIL,
            TemplateStatus.ACTIVE
        )).thenReturn(Optional.of(template));
        when(userPreferenceService.isChannelEnabled(productId, "user-1", "invoice", Channel.EMAIL)).thenReturn(true);
        when(requestRepository.save(any(NotificationRequest.class))).thenAnswer(invocation -> {
            NotificationRequest request = invocation.getArgument(0);
            ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
            return request;
        });
        when(deliveryRepository.save(any(NotificationDelivery.class))).thenAnswer(invocation -> {
            NotificationDelivery delivery = invocation.getArgument(0);
            ReflectionTestUtils.setField(delivery, "id", UUID.randomUUID());
            return delivery;
        });

        NotificationRequest request = service.createNotification(command(productId));

        ArgumentCaptor<NotificationDelivery> deliveryCaptor = ArgumentCaptor.forClass(NotificationDelivery.class);
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(deliveryRepository).save(deliveryCaptor.capture());
        verify(outboxEventRepository).save(outboxCaptor.capture());

        assertThat(request.getStatus()).isEqualTo(NotificationRequestStatus.DELIVERY_CREATED);
        assertThat(deliveryCaptor.getValue().getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(deliveryCaptor.getValue().getDestination()).isEqualTo("user@example.com");
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("NotificationAccepted");
    }

    @Test
    void createNotificationSkipsDeliveryWhenPreferenceDisablesChannel() {
        UUID productId = UUID.randomUUID();
        Product product = new Product("Billing");
        ReflectionTestUtils.setField(product, "id", productId);

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(userPreferenceService.isChannelEnabled(productId, "user-1", "invoice", Channel.EMAIL)).thenReturn(false);
        when(requestRepository.save(any(NotificationRequest.class))).thenAnswer(invocation -> {
            NotificationRequest request = invocation.getArgument(0);
            ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
            return request;
        });

        NotificationRequest request = service.createNotification(command(productId));

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(deliveryRepository, never()).save(any(NotificationDelivery.class));
        verify(outboxEventRepository).save(outboxCaptor.capture());

        assertThat(request.getStatus()).isEqualTo(NotificationRequestStatus.SKIPPED);
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("NotificationSkipped");
    }

    private static CreateNotificationCommand command(UUID productId) {
        return new CreateNotificationCommand(
            productId,
            "invoice.created",
            List.of(Channel.EMAIL),
            "user-1",
            "idem-1",
            "invoice",
            NotificationPriority.HIGH,
            Map.of("name", "Ada"),
            Map.of("email", "user@example.com"),
            null
        );
    }

    private static NotificationTemplate template(Product product) {
        NotificationTemplate template = new NotificationTemplate(
            product,
            "invoice.created",
            Channel.EMAIL,
            1,
            "Hello {{name}}"
        );
        template.setStatus(TemplateStatus.ACTIVE);
        return template;
    }
}
