package com.notificationplatform.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventContractSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void notificationRequestedV1RoundTripsWithoutLosingContractFields() throws Exception {
        NotificationRequested event = new NotificationRequested(
                "event-1", "notification-1", "request-1", "tenant-1", "product-1", "key-1", "welcome",
                new NotificationRequested.Recipient("user-1", "user@example.com", null, null, null),
                List.of("EMAIL"), Map.of("name", "Ada"), Instant.parse("2026-07-10T12:00:00Z"), 1);

        String json = mapper.writeValueAsString(event);
        JsonNode tree = mapper.readTree(json);
        NotificationRequested restored = mapper.readValue(json, NotificationRequested.class);

        assertThat(tree.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(tree.path("productId").asText()).isEqualTo("product-1");
        assertThat(tree.path("recipient").path("email").asText()).isEqualTo("user@example.com");
        assertThat(tree.path("requestedChannels").get(0).asText()).isEqualTo("EMAIL");
        assertThat(tree.path("requestedAt").asText()).isEqualTo("2026-07-10T12:00:00Z");
        assertThat(restored).isEqualTo(event);
    }

    @Test
    void v1ResultAndStatusKeepNullableFailureFields() throws Exception {
        DeliveryResult result = new DeliveryResult(
                "event-2", "notification-1", "delivery-1", "tenant-1", "EMAIL", "DELIVERED", 1,
                "provider-1", null, null, Instant.parse("2026-07-10T12:00:03Z"), 1);
        NotificationStatusChanged status = new NotificationStatusChanged(
                "event-3", "notification-1", "tenant-1", "PROCESSING", null, null,
                Instant.parse("2026-07-10T12:00:01Z"), 1);

        assertThat(mapper.readValue(mapper.writeValueAsString(result), DeliveryResult.class)).isEqualTo(result);
        assertThat(mapper.readValue(mapper.writeValueAsString(status), NotificationStatusChanged.class)).isEqualTo(status);
    }

    @Test
    void aggregateChangeCarriesVersionForStaleEventProtection() throws Exception {
        AggregateChangedEvent event = new AggregateChangedEvent(
                "event-4", "TemplateUpdated", "template-1", 7,
                Instant.parse("2026-07-10T12:00:00Z"), 1, Map.of("subject", "Hello"));

        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(event));

        assertThat(json.path("aggregateVersion").asLong()).isEqualTo(7);
        assertThat(json.path("eventType").asText()).isEqualTo("TemplateUpdated");
    }
}
