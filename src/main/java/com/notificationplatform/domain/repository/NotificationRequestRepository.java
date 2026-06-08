package com.notificationplatform.domain.repository;

import com.notificationplatform.domain.entity.NotificationRequest;
import com.notificationplatform.domain.model.NotificationRequestStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface NotificationRequestRepository extends JpaRepository<NotificationRequest, UUID>, JpaSpecificationExecutor<NotificationRequest> {

    @Override
    @EntityGraph(attributePaths = {"product", "batch"})
    Optional<NotificationRequest> findById(UUID id);

    Optional<NotificationRequest> findByProduct_IdAndIdempotencyKey(UUID productId, String idempotencyKey);

    List<NotificationRequest> findByProduct_IdAndExternalUserIdOrderByCreatedAtDesc(UUID productId, String externalUserId);

    List<NotificationRequest> findByBatch_IdOrderByCreatedAtAsc(UUID batchId);

    List<NotificationRequest> findByStatusOrderByCreatedAtAsc(NotificationRequestStatus status);
}
