package com.notificationplatform.domain.repository;

import com.notificationplatform.domain.entity.NotificationBatch;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationBatchRepository extends JpaRepository<NotificationBatch, UUID> {

    Optional<NotificationBatch> findByProduct_IdAndIdempotencyKey(UUID productId, String idempotencyKey);

    List<NotificationBatch> findByProduct_IdOrderByCreatedAtDesc(UUID productId);
}
