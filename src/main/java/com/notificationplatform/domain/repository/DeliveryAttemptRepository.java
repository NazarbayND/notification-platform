package com.notificationplatform.domain.repository;

import com.notificationplatform.domain.entity.DeliveryAttempt;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {

    List<DeliveryAttempt> findByNotificationDelivery_IdOrderByAttemptNumberAsc(UUID deliveryId);

    Optional<DeliveryAttempt> findByNotificationDelivery_IdAndAttemptNumber(UUID deliveryId, int attemptNumber);
}
