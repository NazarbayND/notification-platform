package com.notificationplatform.application.observability;

import com.notificationplatform.domain.model.DeliveryStatus;
import com.notificationplatform.domain.model.OutboxEventStatus;
import com.notificationplatform.domain.repository.NotificationDeliveryRepository;
import com.notificationplatform.domain.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumSet;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotificationMetricsConfiguration {

    public NotificationMetricsConfiguration(
        MeterRegistry meterRegistry,
        OutboxEventRepository outboxEventRepository,
        NotificationDeliveryRepository deliveryRepository
    ) {
        Gauge.builder("outbox.pending.count", outboxEventRepository,
                repository -> repository.countByStatus(OutboxEventStatus.PENDING))
            .description("Number of pending outbox events")
            .register(meterRegistry);

        Gauge.builder("deliveries.pending.count", deliveryRepository,
                repository -> repository.countByStatusIn(EnumSet.of(DeliveryStatus.PENDING, DeliveryStatus.SENDING)))
            .description("Number of pending or currently sending deliveries")
            .register(meterRegistry);

        Gauge.builder("deliveries.retry.scheduled.count", deliveryRepository,
                repository -> repository.countByStatusIn(EnumSet.of(DeliveryStatus.RETRY_SCHEDULED)))
            .description("Number of deliveries scheduled for retry")
            .register(meterRegistry);

        Gauge.builder("deliveries.dead.lettered.count", deliveryRepository,
                repository -> repository.countByStatusIn(EnumSet.of(DeliveryStatus.DEAD_LETTERED, DeliveryStatus.DLQ)))
            .description("Number of dead-lettered deliveries")
            .register(meterRegistry);
    }
}
