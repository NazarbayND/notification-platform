package com.notificationplatform.web.dto;

public record DashboardStatsResponse(
    long totalNotifications,
    long pendingDeliveries,
    long failedDeliveries,
    long deadLetteredDeliveries
) {
}
