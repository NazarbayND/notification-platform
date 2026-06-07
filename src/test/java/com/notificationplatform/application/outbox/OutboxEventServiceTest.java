package com.notificationplatform.application.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.notificationplatform.domain.entity.OutboxEvent;
import com.notificationplatform.domain.model.OutboxEventStatus;
import com.notificationplatform.domain.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OutboxEventServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-07T00:00:00Z");

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Test
    void markPublishedSetsPublishedStateAndTimestamp() {
        OutboxEvent event = event();
        OutboxEventService service = service();

        when(outboxEventRepository.findById(event.getId())).thenReturn(Optional.of(event));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OutboxEvent result = service.markPublished(event.getId());

        assertThat(result.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(result.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    void recordPublishFailureStoresErrorAndIncrementsAttemptCount() {
        OutboxEvent event = event();
        OutboxEventService service = service();

        when(outboxEventRepository.findById(event.getId())).thenReturn(Optional.of(event));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OutboxEvent result = service.recordPublishFailure(event.getId(), " queue unavailable ");

        assertThat(result.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(result.getAttemptCount()).isEqualTo(1);
        assertThat(result.getLastError()).isEqualTo("queue unavailable");
    }

    private OutboxEventService service() {
        return new OutboxEventService(outboxEventRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OutboxEvent event() {
        OutboxEvent event = new OutboxEvent(
            "NOTIFICATION_REQUEST",
            UUID.randomUUID(),
            "NotificationAccepted",
            Map.of("hello", "world")
        );
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        return event;
    }
}
