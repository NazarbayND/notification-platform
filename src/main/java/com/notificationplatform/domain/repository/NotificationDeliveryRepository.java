package com.notificationplatform.domain.repository;

import com.notificationplatform.domain.entity.NotificationDelivery;
import com.notificationplatform.domain.model.DeliveryStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID>, JpaSpecificationExecutor<NotificationDelivery> {

    @EntityGraph(attributePaths = {"notificationRequest", "template"})
    List<NotificationDelivery> findByNotificationRequest_IdOrderByCreatedAtAsc(UUID notificationRequestId);

    Optional<NotificationDelivery> findByProviderAndProviderMessageId(String provider, String providerMessageId);

    long countByStatusIn(Collection<DeliveryStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"notificationRequest", "template"})
    @Query("""
        select delivery
        from NotificationDelivery delivery
        where delivery.id = :deliveryId
        """)
    Optional<NotificationDelivery> findByIdForUpdate(@Param("deliveryId") UUID deliveryId);

    @EntityGraph(attributePaths = {"notificationRequest", "template"})
    @Query("""
        select delivery
        from NotificationDelivery delivery
        where delivery.id = :deliveryId
        """)
    Optional<NotificationDelivery> findByIdWithRequestAndTemplate(@Param("deliveryId") UUID deliveryId);

    @EntityGraph(attributePaths = {"notificationRequest", "template"})
    @Query("""
        select delivery
        from NotificationDelivery delivery
        where delivery.id in :deliveryIds
        """)
    List<NotificationDelivery> findAllByIdInWithRequestAndTemplate(@Param("deliveryIds") Collection<UUID> deliveryIds);

    @EntityGraph(attributePaths = {"notificationRequest", "template"})
    @Query("""
        select delivery
        from NotificationDelivery delivery
        where delivery.status in :statuses
          and (delivery.nextAttemptAt is null or delivery.nextAttemptAt <= :now)
          and (delivery.expiresAt is null or delivery.expiresAt > :now)
        order by
          case when delivery.nextAttemptAt is null then 0 else 1 end,
          delivery.nextAttemptAt asc,
          delivery.createdAt asc
        """)
    List<NotificationDelivery> findReadyForAttempt(
        @Param("statuses") Collection<DeliveryStatus> statuses,
        @Param("now") Instant now,
        Pageable pageable
    );

    @EntityGraph(attributePaths = {"notificationRequest", "template"})
    @Query("""
        select delivery
        from NotificationDelivery delivery
        where (
            (
                delivery.status in :retryStatuses
                and (delivery.nextAttemptAt is null or delivery.nextAttemptAt <= :now)
            ) or (
                delivery.status = :sendingStatus
                and delivery.lockedUntil is not null
                and delivery.lockedUntil <= :now
            )
        )
          and (delivery.expiresAt is null or delivery.expiresAt > :now)
        order by
          case
            when delivery.status = :sendingStatus then 0
            when delivery.nextAttemptAt is null then 1
            else 2
          end,
          delivery.lockedUntil asc,
          delivery.nextAttemptAt asc,
          delivery.createdAt asc
        """)
    List<NotificationDelivery> findReadyForRetryOrExpiredSending(
        @Param("retryStatuses") Collection<DeliveryStatus> retryStatuses,
        @Param("sendingStatus") DeliveryStatus sendingStatus,
        @Param("now") Instant now,
        Pageable pageable
    );

}
