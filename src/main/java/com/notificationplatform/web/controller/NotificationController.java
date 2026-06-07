package com.notificationplatform.web.controller;

import com.notificationplatform.application.notification.BatchNotificationItem;
import com.notificationplatform.application.notification.CreateNotificationBatchCommand;
import com.notificationplatform.application.notification.CreateNotificationCommand;
import com.notificationplatform.application.notification.NotificationSubmissionService;
import com.notificationplatform.web.dto.BatchNotificationItemRequest;
import com.notificationplatform.web.dto.NotificationBatchResponse;
import com.notificationplatform.web.dto.NotificationResponse;
import com.notificationplatform.web.dto.SendNotificationBatchRequest;
import com.notificationplatform.web.dto.SendNotificationRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class NotificationController {

    private final NotificationSubmissionService notificationSubmissionService;

    public NotificationController(NotificationSubmissionService notificationSubmissionService) {
        this.notificationSubmissionService = notificationSubmissionService;
    }

    @PostMapping("/notifications")
    @ResponseStatus(HttpStatus.CREATED)
    public NotificationResponse sendNotification(@Valid @RequestBody SendNotificationRequest request) {
        return NotificationResponse.from(notificationSubmissionService.createNotification(new CreateNotificationCommand(
            request.productId(),
            request.templateKey(),
            request.channel(),
            request.externalUserId(),
            request.idempotencyKey(),
            request.category(),
            request.priority(),
            request.payload(),
            request.recipient()
        )));
    }

    @PostMapping("/notification-batches")
    @ResponseStatus(HttpStatus.CREATED)
    public NotificationBatchResponse sendNotificationBatch(@Valid @RequestBody SendNotificationBatchRequest request) {
        return NotificationBatchResponse.from(notificationSubmissionService.createNotificationBatch(
            new CreateNotificationBatchCommand(
                request.productId(),
                request.idempotencyKey(),
                request.items().stream()
                    .map(NotificationController::toBatchItem)
                    .toList()
            )
        ));
    }

    @GetMapping("/notifications/{id}")
    public NotificationResponse getNotification(@PathVariable UUID id) {
        return NotificationResponse.from(notificationSubmissionService.getNotification(id));
    }

    private static BatchNotificationItem toBatchItem(BatchNotificationItemRequest request) {
        return new BatchNotificationItem(
            request.templateKey(),
            request.channel(),
            request.externalUserId(),
            request.idempotencyKey(),
            request.category(),
            request.priority(),
            request.payload(),
            request.recipient()
        );
    }
}
