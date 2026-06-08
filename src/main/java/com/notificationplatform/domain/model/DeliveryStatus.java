package com.notificationplatform.domain.model;

public enum DeliveryStatus {
    PENDING,
    PROCESSING,
    SENDING,
    SENT,
    DELIVERED,
    FAILED,
    RETRY_SCHEDULED,
    DLQ,
    DEAD_LETTERED,
    SKIPPED
}
