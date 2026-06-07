package com.notificationplatform.domain.repository;

import com.notificationplatform.domain.entity.OutboxEvent;
import com.notificationplatform.domain.model.OutboxEventStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(String aggregateType, UUID aggregateId);

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
}
