package com.notificationplatform.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.notificationplatform.contracts.DeliveryResult;
import com.notificationplatform.contracts.NotificationRequested;
import com.notificationplatform.contracts.NotificationStatusChanged;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@EnabledIfEnvironmentVariable(named = "RUN_DYNAMODB_CONTRACT_TESTS", matches = "1")
class DynamoDbNotificationProjectionRepositoryContractTest {
    private static DynamoDbClient client;
    private static DynamoDbNotificationProjectionRepository repository;

    @BeforeAll
    static void setUp() {
        String endpoint = System.getenv().getOrDefault("DYNAMODB_ENDPOINT", "http://localhost:4566");
        client = DynamoDbClient.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        repository = new DynamoDbNotificationProjectionRepository(client,
                "notification-projections", "notification-deliveries", "notification-delivery-attempts",
                "notification-processed-events", 1);
        repository.clearForRebuild();
    }

    @AfterAll
    static void close() {
        if (client != null) client.close();
    }

    @Test
    void projectsRequestsStatusesResultsAndDuplicateEventsIdempotently() {
        Instant requestedAt = Instant.now();
        NotificationRequested requested = new NotificationRequested(
                "request-event-1", "notification-1", "request-1", "tenant-1", "product-1", "key-1", "welcome",
                new NotificationRequested.Recipient("user-1", "user@example.com", null, null, null),
                List.of("EMAIL"), Map.of("name", "Ada"), requestedAt, 1);

        repository.upsertAcceptedNotification(requested);
        repository.upsertAcceptedNotification(requested);
        repository.updateNotificationStatus(new NotificationStatusChanged(
                "status-event-1", "notification-1", "tenant-1", "PROCESSING", null, null,
                requestedAt.plusSeconds(1), 1));
        DeliveryResult result = new DeliveryResult(
                "result-event-1", "notification-1", "delivery-1", "tenant-1", "EMAIL", "DELIVERED", 1,
                "provider-1", null, null, requestedAt.plusSeconds(2), 1);
        repository.appendDeliveryAttempt(result);
        repository.appendDeliveryAttempt(result);

        NotificationView notification = repository.findById("notification-1").orElseThrow();
        List<DeliveryView> deliveries = repository.findDeliveries("notification-1");

        assertThat(notification.status()).isEqualTo("DELIVERED");
        assertThat(notification.productId()).isEqualTo("product-1");
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.getFirst().status()).isEqualTo("DELIVERED");
        assertThat(repository.findAll("tenant-1", "product-1", "DELIVERED", "EMAIL", 0, 20).items())
                .extracting(NotificationView::notificationId).containsExactly("notification-1");
        assertThat(repository.findByUser("tenant-1", "user-1", 0, 20).items())
                .extracting(NotificationView::notificationId).containsExactly("notification-1");
    }
}
