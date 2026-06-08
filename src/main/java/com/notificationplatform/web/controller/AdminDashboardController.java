package com.notificationplatform.web.controller;

import com.notificationplatform.application.delivery.NotificationDeliveryService;
import com.notificationplatform.application.notification.NotificationSubmissionService;
import com.notificationplatform.web.dto.DashboardStatsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
public class AdminDashboardController {

    private final NotificationSubmissionService notificationSubmissionService;
    private final NotificationDeliveryService notificationDeliveryService;

    public AdminDashboardController(
        NotificationSubmissionService notificationSubmissionService,
        NotificationDeliveryService notificationDeliveryService
    ) {
        this.notificationSubmissionService = notificationSubmissionService;
        this.notificationDeliveryService = notificationDeliveryService;
    }

    @GetMapping
    public DashboardStatsResponse getStats() {
        return new DashboardStatsResponse(
            notificationSubmissionService.countNotifications(),
            notificationDeliveryService.countPendingDeliveries(),
            notificationDeliveryService.countFailedDeliveries(),
            notificationDeliveryService.countDeadLetteredDeliveries()
        );
    }
}
