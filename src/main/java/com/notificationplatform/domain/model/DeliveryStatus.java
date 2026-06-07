package com.notificationplatform.domain.model;

public enum DeliveryStatus {
    PENDING,
    PROCESSING,
    SENT,
    DELIVERED,
    FAILED,
    RETRY_SCHEDULED,
    DLQ,
    SKIPPED
}
