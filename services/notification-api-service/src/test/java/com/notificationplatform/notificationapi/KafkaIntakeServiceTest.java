package com.notificationplatform.notificationapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.notificationplatform.contracts.NotificationRequested;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

class KafkaIntakeServiceTest {

    @Test
    void publishesDurablyBeforeReturningAccepted() {
        KafkaTemplate<Object, Object> kafka = kafkaTemplate();
        AcceptanceCache cache = mock(AcceptanceCache.class);
        when(cache.findByIdempotency("tenant-1", "key-1")).thenReturn(Optional.empty());
        when(kafka.send(eq("notification.requests.v1"), eq("tenant-1:user-1"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        KafkaNotificationIntakeService service = new KafkaNotificationIntakeService(
                kafka, new NotificationKafkaTopics(), new NotificationIntakeProperties(), cache, meters);

        NotificationApiServiceApplication.NotificationAccepted accepted = service.submit(request(), "correlation-1", "tenant-1");

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq("notification.requests.v1"), eq("tenant-1:user-1"), eventCaptor.capture());
        NotificationRequested event = (NotificationRequested) eventCaptor.getValue();
        assertThat(event.notificationId()).isEqualTo(accepted.notificationId().toString());
        assertThat(event.requestId()).isEqualTo(accepted.requestId().toString());
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.requestedChannels()).containsExactly("EMAIL");
        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        verify(cache).store("tenant-1", "key-1", accepted);
    }

    @Test
    void returnsCachedAcceptanceForAnIdempotentRetryWithoutRepublishing() {
        KafkaTemplate<Object, Object> kafka = kafkaTemplate();
        AcceptanceCache cache = mock(AcceptanceCache.class);
        NotificationApiServiceApplication.NotificationAccepted previous =
                new NotificationApiServiceApplication.NotificationAccepted(
                        java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "ACCEPTED",
                        java.time.Instant.now(), "old-correlation", "EMAIL");
        when(cache.findByIdempotency("tenant-1", "key-1")).thenReturn(Optional.of(previous));
        KafkaNotificationIntakeService service = new KafkaNotificationIntakeService(
                kafka, new NotificationKafkaTopics(), new NotificationIntakeProperties(), cache,
                new SimpleMeterRegistry());

        assertThat(service.submit(request(), "new-correlation", "tenant-1")).isEqualTo(previous);
        org.mockito.Mockito.verifyNoInteractions(kafka);
    }

    @Test
    void failsFastWhenKafkaDoesNotAcknowledgeWithinTheConfiguredTimeout() {
        KafkaTemplate<Object, Object> kafka = kafkaTemplate();
        AcceptanceCache cache = mock(AcceptanceCache.class);
        when(cache.findByIdempotency("tenant-1", "key-1")).thenReturn(Optional.empty());
        when(kafka.send(eq("notification.requests.v1"), eq("tenant-1:user-1"), any()))
                .thenReturn(new CompletableFuture<>());
        NotificationIntakeProperties properties = new NotificationIntakeProperties();
        properties.setKafkaPublishTimeout(Duration.ofMillis(1));
        KafkaNotificationIntakeService service = new KafkaNotificationIntakeService(
                kafka, new NotificationKafkaTopics(), properties, cache, new SimpleMeterRegistry());

        assertThatThrownBy(() -> service.submit(request(), "correlation-1", "tenant-1"))
                .isInstanceOf(KafkaPublishException.class)
                .hasMessageContaining("durably accept");
    }

    @Test
    void partitionKeyCombinesTenantAndRecipient() {
        assertThat(NotificationIntakeService.partitionKey("tenant-a", "user-b"))
                .isEqualTo("tenant-a:user-b");
    }

    @Test
    void requestValidationRejectsUnsupportedChannelAndBlankIdempotencyKey() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        var invalid = new NotificationApiServiceApplication.NotificationRequest(
                "user-1", "product-1", "FAX", "welcome", Map.of(), "", "destination",
                "tenant-1", null);

        assertThat(validator.validate(invalid))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("channel", "idempotencyKey");
    }

    @Test
    void redisAdmissionRejectsRequestsOverTheConfiguredRate() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), any())).thenReturn(1L, 1L, 2L);
        NotificationIntakeProperties properties = new NotificationIntakeProperties();
        properties.setGlobalRatePerSecond(1);
        properties.setPerTenantRatePerSecond(1);
        RedisAdmissionControl admission = new RedisAdmissionControl(redis, properties, new SimpleMeterRegistry());

        assertThat(admission.execute("tenant-1", () -> "accepted")).isEqualTo("accepted");
        assertThatThrownBy(() -> admission.execute("tenant-1", () -> "rejected"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void concurrencyLimitShedsLoadWithoutWaiting() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), any())).thenReturn(1L);
        NotificationIntakeProperties properties = new NotificationIntakeProperties();
        properties.setMaxConcurrentRequests(1);
        RedisAdmissionControl admission = new RedisAdmissionControl(redis, properties, new SimpleMeterRegistry());

        assertThatThrownBy(() -> admission.execute(
                "tenant-1", () -> admission.execute("tenant-2", () -> "unreachable")))
                .isInstanceOf(IntakeCapacityExceededException.class);
    }

    private NotificationApiServiceApplication.NotificationRequest request() {
        return new NotificationApiServiceApplication.NotificationRequest(
                "user-1", "product-1", "EMAIL", "welcome", Map.of("name", "Ada"),
                "key-1", "user@example.com", "tenant-1", null);
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<Object, Object> kafkaTemplate() {
        return mock(KafkaTemplate.class);
    }
}
