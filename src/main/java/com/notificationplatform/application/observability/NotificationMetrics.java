package com.notificationplatform.application.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class NotificationMetrics {

    private final Counter notificationsCreated;
    private final Counter notificationBatchesCreated;
    private final Counter outboxEventsCreated;
    private final Counter outboxEventsPublished;
    private final Counter outboxEventsFailed;
    private final Counter deliveryAttempts;
    private final Counter deliveriesSent;
    private final Counter deliveriesFailed;
    private final Counter deliveriesDeadLettered;
    private final Counter rabbitMqMessagesPublished;
    private final Counter rabbitMqMessagesConsumed;
    private final Counter emailProviderSendSuccess;
    private final Counter emailProviderSendFailure;
    private final Counter redisCacheHit;
    private final Counter redisCacheMiss;
    private final Counter redisCacheEviction;
    private final AtomicInteger outboxPublishBatchSize;
    private final AtomicReference<Double> outboxPublishLagSeconds;
    private final Timer notificationCreateDuration;
    private final Timer outboxPublishDuration;
    private final Timer deliveryProcessingDuration;
    private final Timer emailProviderSendDuration;

    public NotificationMetrics(MeterRegistry meterRegistry) {
        this.notificationsCreated = meterRegistry.counter("notifications.created");
        this.notificationBatchesCreated = meterRegistry.counter("notification.batches.created");
        this.outboxEventsCreated = meterRegistry.counter("outbox.events.created");
        this.outboxEventsPublished = meterRegistry.counter("outbox.events.published");
        this.outboxEventsFailed = meterRegistry.counter("outbox.events.failed");
        this.deliveryAttempts = meterRegistry.counter("delivery.attempts");
        this.deliveriesSent = meterRegistry.counter("deliveries.sent");
        this.deliveriesFailed = meterRegistry.counter("deliveries.failed");
        this.deliveriesDeadLettered = meterRegistry.counter("deliveries.dead.lettered");
        this.rabbitMqMessagesPublished = meterRegistry.counter("rabbitmq.messages.published");
        this.rabbitMqMessagesConsumed = meterRegistry.counter("rabbitmq.messages.consumed");
        this.emailProviderSendSuccess = meterRegistry.counter("email.provider.send.success");
        this.emailProviderSendFailure = meterRegistry.counter("email.provider.send.failure");
        this.redisCacheHit = meterRegistry.counter("redis.cache.hit");
        this.redisCacheMiss = meterRegistry.counter("redis.cache.miss");
        this.redisCacheEviction = meterRegistry.counter("redis.cache.eviction");
        this.outboxPublishBatchSize = new AtomicInteger();
        this.outboxPublishLagSeconds = new AtomicReference<>(0.0);
        Gauge.builder("outbox.publish.batch.size", outboxPublishBatchSize, AtomicInteger::get)
            .description("Number of outbox events fetched in the last publisher batch")
            .register(meterRegistry);
        Gauge.builder("outbox.publish.lag.seconds", outboxPublishLagSeconds, AtomicReference::get)
            .description("Age in seconds of the oldest outbox event fetched in the last publisher batch")
            .register(meterRegistry);
        this.notificationCreateDuration = meterRegistry.timer("notification.create.duration");
        this.outboxPublishDuration = meterRegistry.timer("outbox.publish.duration");
        this.deliveryProcessingDuration = meterRegistry.timer("delivery.processing.duration");
        this.emailProviderSendDuration = meterRegistry.timer("email.provider.send.duration");
    }

    public void incrementNotificationsCreated() {
        notificationsCreated.increment();
    }

    public void incrementNotificationBatchesCreated() {
        notificationBatchesCreated.increment();
    }

    public void incrementOutboxEventsCreated() {
        outboxEventsCreated.increment();
    }

    public void incrementOutboxEventsPublished() {
        outboxEventsPublished.increment();
    }

    public void incrementOutboxEventsFailed() {
        outboxEventsFailed.increment();
    }

    public void incrementDeliveryAttempts() {
        deliveryAttempts.increment();
    }

    public void incrementDeliveriesSent() {
        deliveriesSent.increment();
    }

    public void incrementDeliveriesFailed() {
        deliveriesFailed.increment();
    }

    public void incrementDeliveriesDeadLettered() {
        deliveriesDeadLettered.increment();
    }

    public void incrementRabbitMqMessagesPublished() {
        rabbitMqMessagesPublished.increment();
    }

    public void incrementRabbitMqMessagesConsumed() {
        rabbitMqMessagesConsumed.increment();
    }

    public void incrementEmailProviderSendSuccess() {
        emailProviderSendSuccess.increment();
    }

    public void incrementEmailProviderSendFailure() {
        emailProviderSendFailure.increment();
    }

    public void incrementRedisCacheHit() {
        redisCacheHit.increment();
    }

    public void incrementRedisCacheMiss() {
        redisCacheMiss.increment();
    }

    public void incrementRedisCacheEviction() {
        redisCacheEviction.increment();
    }

    public void recordOutboxPublishBatchSize(int batchSize) {
        outboxPublishBatchSize.set(Math.max(0, batchSize));
    }

    public void recordOutboxPublishLagSeconds(double lagSeconds) {
        outboxPublishLagSeconds.set(Math.max(0.0, lagSeconds));
    }

    public <T> T recordNotificationCreate(Supplier<T> operation) {
        return notificationCreateDuration.record(operation);
    }

    public void recordOutboxPublish(Runnable operation) {
        outboxPublishDuration.record(operation);
    }

    public <T> T recordOutboxPublish(Supplier<T> operation) {
        return outboxPublishDuration.record(operation);
    }

    public void recordDeliveryProcessing(Runnable operation) {
        deliveryProcessingDuration.record(operation);
    }

    public <T> T recordEmailProviderSend(Supplier<T> operation) {
        return emailProviderSendDuration.record(operation);
    }
}
