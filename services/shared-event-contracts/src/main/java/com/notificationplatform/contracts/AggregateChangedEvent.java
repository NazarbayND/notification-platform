package com.notificationplatform.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.Map;

public record AggregateChangedEvent(
        String eventId,
        String eventType,
        String aggregateId,
        long aggregateVersion,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
        int schemaVersion,
        Map<String, Object> payload) {

    public AggregateChangedEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
