package com.notificationplatform.domain.repository;

import com.notificationplatform.domain.entity.OutboxEvent;
import com.notificationplatform.domain.model.OutboxEventStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(String aggregateType, UUID aggregateId);

    long countByStatus(OutboxEventStatus status);

    @Query("""
        select event
        from OutboxEvent event
        where event.status = :status
          and event.availableAt <= :now
        order by event.availableAt asc, event.createdAt asc
        """)
    List<OutboxEvent> findAvailableEvents(
        @Param("status") OutboxEventStatus status,
        @Param("now") Instant now,
        Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select event
        from OutboxEvent event
        where event.id = :eventId
        """)
    Optional<OutboxEvent> findByIdForUpdate(@Param("eventId") UUID eventId);

    default List<OutboxEvent> findReadyPendingEventsForPublishing(Instant now, int limit) {
        return findReadyPendingEventsForPublishing(OutboxEventStatus.PENDING.name(), now, limit);
    }

    @Query(value = """
        select *
        from outbox_events
        where status = :status
          and available_at <= :now
        order by available_at asc, created_at asc
        limit :limit
        for update skip locked
        """, nativeQuery = true)
    List<OutboxEvent> findReadyPendingEventsForPublishing(
        @Param("status") String status,
        @Param("now") Instant now,
        @Param("limit") int limit
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update OutboxEvent event
        set event.status = :publishedStatus,
            event.publishedAt = :publishedAt,
            event.lastError = null
        where event.id in :eventIds
          and event.status = :pendingStatus
        """)
    int markEventsPublished(
        @Param("eventIds") List<UUID> eventIds,
        @Param("publishedStatus") OutboxEventStatus publishedStatus,
        @Param("pendingStatus") OutboxEventStatus pendingStatus,
        @Param("publishedAt") Instant publishedAt
    );
}
