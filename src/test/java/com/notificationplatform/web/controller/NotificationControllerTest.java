package com.notificationplatform.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notificationplatform.application.delivery.NotificationDeliveryService;
import com.notificationplatform.application.common.ResourceNotFoundException;
import com.notificationplatform.application.notification.NotificationSubmissionService;
import com.notificationplatform.domain.entity.NotificationBatch;
import com.notificationplatform.domain.entity.NotificationRequest;
import com.notificationplatform.domain.entity.NotificationTemplate;
import com.notificationplatform.domain.entity.Product;
import com.notificationplatform.domain.model.BatchStatus;
import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.NotificationPriority;
import com.notificationplatform.domain.model.NotificationRequestStatus;
import com.notificationplatform.domain.model.TemplateStatus;
import com.notificationplatform.web.error.GlobalExceptionHandler;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NotificationController.class)
@Import(GlobalExceptionHandler.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotificationSubmissionService notificationSubmissionService;

    @MockBean
    private NotificationDeliveryService notificationDeliveryService;

    @Test
    void sendNotificationReturnsCreatedNotification() throws Exception {
        NotificationRequest notification = notification();
        when(notificationSubmissionService.createNotification(any())).thenReturn(notification);

        mockMvc.perform(post("/api/v1/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "productId": "%s",
                      "templateKey": "invoice.created",
                      "requestedChannels": ["EMAIL"],
                      "externalUserId": "user-1",
                      "idempotencyKey": "idem-1",
                      "category": "invoice",
                      "priority": "HIGH",
                      "payload": {
                        "name": "Ada"
                      },
                      "recipient": {
                        "email": "user@example.com"
                      }
                    }
                    """.formatted(notification.getProduct().getId())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(notification.getId().toString()))
            .andExpect(jsonPath("$.status").value("DELIVERY_CREATED"))
            .andExpect(jsonPath("$.priority").value("HIGH"));
    }

    @Test
    void sendNotificationBatchReturnsCreatedBatch() throws Exception {
        Product product = product();
        NotificationBatch batch = new NotificationBatch(product, "batch-1", 1);
        batch.setStatus(BatchStatus.COMPLETED);
        batch.setAcceptedCount(1);
        ReflectionTestUtils.setField(batch, "id", UUID.randomUUID());

        when(notificationSubmissionService.createNotificationBatch(any())).thenReturn(batch);

        mockMvc.perform(post("/api/v1/notification-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "productId": "%s",
                      "idempotencyKey": "batch-1",
                      "items": [
                        {
                          "templateKey": "invoice.created",
                          "requestedChannels": ["EMAIL"],
                          "externalUserId": "user-1",
                          "idempotencyKey": "idem-1",
                          "category": "invoice",
                          "recipient": {
                            "email": "user@example.com"
                          }
                        }
                      ]
                    }
                    """.formatted(product.getId())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(batch.getId().toString()))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.acceptedCount").value(1));
    }

    @Test
    void getNotificationReturnsNotFoundForMissingNotification() throws Exception {
        UUID notificationId = UUID.randomUUID();
        when(notificationSubmissionService.getNotification(notificationId))
            .thenThrow(new ResourceNotFoundException("Notification request not found: " + notificationId));

        mockMvc.perform(get("/api/v1/notifications/{id}", notificationId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("Notification request not found: " + notificationId));
    }

    private static NotificationRequest notification() {
        Product product = product();
        NotificationTemplate template = new NotificationTemplate(product, "invoice.created", Channel.EMAIL, 1, "Hello");
        template.setStatus(TemplateStatus.ACTIVE);
        ReflectionTestUtils.setField(template, "id", UUID.randomUUID());

        NotificationRequest request = new NotificationRequest(product, "invoice.created", "user-1", "idem-1", "invoice");
        request.setRequestedChannels(List.of(Channel.EMAIL));
        request.setPriority(NotificationPriority.HIGH);
        request.setStatus(NotificationRequestStatus.DELIVERY_CREATED);
        request.setPayload(Map.of("name", "Ada"));
        request.setRecipient(Map.of("email", "user@example.com"));
        ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
        return request;
    }

    private static Product product() {
        Product product = new Product("Billing");
        ReflectionTestUtils.setField(product, "id", UUID.randomUUID());
        return product;
    }
}
