package com.notificationplatform.web.controller;

import com.notificationplatform.application.delivery.NotificationDeliveryService;
import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.DeliveryStatus;
import com.notificationplatform.web.dto.DeliveryResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/deliveries")
public class AdminDeliveryController {

    private final NotificationDeliveryService notificationDeliveryService;

    public AdminDeliveryController(NotificationDeliveryService notificationDeliveryService) {
        this.notificationDeliveryService = notificationDeliveryService;
    }

    @GetMapping
    public List<DeliveryResponse> listDeliveries(
        @RequestParam(required = false) UUID notificationRequestId,
        @RequestParam(required = false) DeliveryStatus status,
        @RequestParam(required = false) Channel channel,
        @RequestParam(required = false) String provider,
        @RequestParam(defaultValue = "100") int limit
    ) {
        return notificationDeliveryService.listDeliveries(notificationRequestId, status, channel, provider, limit).stream()
            .map(DeliveryResponse::from)
            .toList();
    }
}
