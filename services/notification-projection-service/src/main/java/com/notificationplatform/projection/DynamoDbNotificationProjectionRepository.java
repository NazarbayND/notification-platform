package com.notificationplatform.projection;

import com.notificationplatform.contracts.DeliveryResult;
import com.notificationplatform.contracts.NotificationRequested;
import com.notificationplatform.contracts.NotificationStatusChanged;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "notification.projection.store", havingValue = "dynamodb")
class DynamoDbProjectionConfiguration {
    @Bean(destroyMethod = "close")
    DynamoDbClient projectionDynamoDbClient(
            @Value("${notification.projection.dynamodb.region}") String region,
            @Value("${notification.projection.dynamodb.endpoint:}") String endpoint,
            @Value("${notification.projection.dynamodb.access-key}") String accessKey,
            @Value("${notification.projection.dynamodb.secret-key}") String secretKey) {
        var builder = DynamoDbClient.builder().region(Region.of(region)).httpClientBuilder(UrlConnectionHttpClient.builder());
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        if (endpoint != null && !endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }
}

@Component("dynamoDbProjectionHealthIndicator")
@ConditionalOnProperty(name = "notification.projection.store", havingValue = "dynamodb")
class DynamoDbProjectionHealthIndicator implements HealthIndicator {
    private final DynamoDbClient dynamo;
    private final List<String> tables;
    DynamoDbProjectionHealthIndicator(DynamoDbClient dynamo,
            @Value("${notification.projection.dynamodb.notifications-table}") String notifications,
            @Value("${notification.projection.dynamodb.deliveries-table}") String deliveries,
            @Value("${notification.projection.dynamodb.attempts-table}") String attempts,
            @Value("${notification.projection.dynamodb.processed-events-table}") String processed) {
        this.dynamo=dynamo;this.tables=List.of(notifications,deliveries,attempts,processed);
    }
    @Override public Health health(){try{for(String table:tables)dynamo.describeTable(request->request.tableName(table));
        return Health.up().withDetail("store","dynamodb").withDetail("tables",tables).build();
    }catch(RuntimeException exception){return Health.down().withException(exception).withDetail("store","dynamodb").build();}}
}

@Repository
@ConditionalOnProperty(name = "notification.projection.store", havingValue = "dynamodb")
class DynamoDbNotificationProjectionRepository implements NotificationProjectionRepository {
    private static final String TENANT_TIME_INDEX = "tenant-requested-index";
    private static final String NOTIFICATION_TIME_INDEX = "notification-updated-index";
    private static final String USER_TIME_INDEX = "user-requested-index";

    private final DynamoDbClient dynamo;
    private final String notificationsTable;
    private final String deliveriesTable;
    private final String attemptsTable;
    private final String processedTable;
    private final int retentionDays;

    DynamoDbNotificationProjectionRepository(
            DynamoDbClient dynamo,
            @Value("${notification.projection.dynamodb.notifications-table}") String notificationsTable,
            @Value("${notification.projection.dynamodb.deliveries-table}") String deliveriesTable,
            @Value("${notification.projection.dynamodb.attempts-table}") String attemptsTable,
            @Value("${notification.projection.dynamodb.processed-events-table}") String processedTable,
            @Value("${notification.projection.retention-days:90}") int retentionDays) {
        this.dynamo = dynamo;
        this.notificationsTable = notificationsTable;
        this.deliveriesTable = deliveriesTable;
        this.attemptsTable = attemptsTable;
        this.processedTable = processedTable;
        this.retentionDays = Math.max(1, retentionDays);
    }

    @Override
    public void upsertAcceptedNotification(NotificationRequested event) {
        Map<String, String> names = Map.of(
                "#request", "requestId", "#tenant", "tenantId", "#product", "productId", "#user", "userId",
                "#template", "templateId", "#channels", "requestedChannels", "#requested", "requestedAtEpoch",
                "#updated", "updatedAtEpoch", "#status", "status", "#ttl", "expiresAt");
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":request", s(event.requestId()));
        values.put(":tenant", s(event.tenantId()));
        values.put(":product", s(event.productId()));
        values.put(":user", s(event.recipient().userId()));
        values.put(":tenantUser", s(event.tenantId() + "#" + event.recipient().userId()));
        values.put(":template", s(event.templateId()));
        values.put(":channels", AttributeValue.builder().l(event.requestedChannels().stream().map(DynamoDbNotificationProjectionRepository::s).toList()).build());
        values.put(":requested", n(epoch(event.requestedAt())));
        values.put(":accepted", s("ACCEPTED"));
        values.put(":ttl", n(ttl(event.requestedAt())));
        Update update = Update.builder().tableName(notificationsTable).key(key("notificationId", event.notificationId()))
                .updateExpression("SET #request=:request,#tenant=:tenant,#product=:product,#user=:user,#template=:template," +
                        "tenantUserKey=:tenantUser,#channels=:channels,#requested=:requested,#updated=if_not_exists(#updated,:requested)," +
                        "#status=if_not_exists(#status,:accepted),#ttl=:ttl")
                .expressionAttributeNames(names).expressionAttributeValues(values).build();
        transact("requests", event.eventId(), List.of(TransactWriteItem.builder().update(update).build()));
    }

    @Override
    public void updateNotificationStatus(NotificationStatusChanged event) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":tenant", s(event.tenantId()));
        values.put(":status", s(event.status()));
        values.put(":code", nullable(event.reasonCode()));
        values.put(":message", nullable(event.reasonMessage()));
        values.put(":updated", n(epoch(event.occurredAt())));
        values.put(":ttl", n(ttl(event.occurredAt())));
        Update update = Update.builder().tableName(notificationsTable).key(key("notificationId", event.notificationId()))
                .updateExpression("SET tenantId=:tenant,#status=:status,reasonCode=:code,reasonMessage=:message,updatedAtEpoch=:updated,expiresAt=:ttl")
                .expressionAttributeNames(Map.of("#status", "status")).expressionAttributeValues(values).build();
        transact("status", event.eventId(), List.of(TransactWriteItem.builder().update(update).build()));
    }

    @Override
    public void upsertDelivery(DeliveryResult event) {
        dynamo.updateItem(deliveryUpdate(event));
    }

    @Override
    public void appendDeliveryAttempt(DeliveryResult event) {
        Map<String, AttributeValue> attempt = new HashMap<>();
        attempt.put("eventId", s(event.eventId()));
        attempt.put("deliveryId", s(event.deliveryId()));
        attempt.put("notificationId", s(event.notificationId()));
        attempt.put("attempt", n(event.attempt()));
        attempt.put("status", s(event.status()));
        attempt.put("providerMessageId", nullable(event.providerMessageId()));
        attempt.put("errorCode", nullable(event.errorCode()));
        attempt.put("errorMessage", nullable(event.errorMessage()));
        attempt.put("occurredAtEpoch", n(epoch(event.occurredAt())));
        attempt.put("expiresAt", n(ttl(event.occurredAt())));
        Put attemptPut = Put.builder().tableName(attemptsTable).item(attempt)
                .conditionExpression("attribute_not_exists(eventId)").build();
        boolean applied = transact("results", event.eventId(), List.of(
                TransactWriteItem.builder().put(attemptPut).build(),
                TransactWriteItem.builder().update(deliveryUpdateModel(event)).build()));
        if (applied || isProcessed("results", event.eventId())) aggregate(event.notificationId(), event.occurredAt());
    }

    @Override
    public Optional<NotificationView> findById(String notificationId) {
        Map<String, AttributeValue> item = dynamo.getItem(GetItemRequest.builder().tableName(notificationsTable)
                .key(key("notificationId", notificationId)).consistentRead(true).build()).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(notification(item));
    }

    @Override
    public PageView<NotificationView> findAll(String tenantId, String productId, String status, String channel, int page, int size) {
        List<Map<String, AttributeValue>> raw = tenantId == null ? scan(notificationsTable) : queryTenant(tenantId);
        List<NotificationView> filtered = raw.stream().map(this::notification)
                .filter(value -> productId == null || productId.equals(value.productId()))
                .filter(value -> status == null || status.equalsIgnoreCase(value.status()))
                .filter(value -> channel == null || channel.equalsIgnoreCase(value.channel()))
                .sorted(Comparator.comparing(NotificationView::requestedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))).toList();
        int limit = Math.max(1, Math.min(size, 200));
        int from = Math.min(filtered.size(), Math.max(0, page) * limit);
        int to = Math.min(filtered.size(), from + limit);
        return new PageView<>(filtered.subList(from, to), filtered.size(), Math.max(0, page), limit);
    }

    @Override
    public PageView<NotificationView> findByUser(String tenantId, String userId, int page, int size) {
        List<NotificationView> filtered = (tenantId == null ? scan(notificationsTable) : queryUser(tenantId, userId)).stream()
                .map(this::notification).filter(value -> userId.equals(value.userId()))
                .sorted(Comparator.comparing(NotificationView::requestedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))).toList();
        int limit=Math.max(1,Math.min(size,200));int from=Math.min(filtered.size(),Math.max(0,page)*limit);
        return new PageView<>(filtered.subList(from,Math.min(filtered.size(),from+limit)),filtered.size(),Math.max(0,page),limit);
    }

    @Override
    public List<DeliveryView> findDeliveries(String notificationId) {
        return findDeliveries(notificationId, null, null, 0, 200);
    }

    @Override
    public List<DeliveryView> findDeliveries(String notificationId, String status, String channel, int page, int size) {
        List<Map<String, AttributeValue>> raw = notificationId == null ? scan(deliveriesTable) : queryDeliveries(notificationId);
        List<DeliveryView> filtered = raw.stream().map(this::delivery)
                .filter(value -> status == null || status.equalsIgnoreCase(value.status()))
                .filter(value -> channel == null || channel.equalsIgnoreCase(value.channel()))
                .sorted(Comparator.comparing(DeliveryView::updatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))).toList();
        int limit = Math.max(1, Math.min(size, 200));
        int from = Math.min(filtered.size(), Math.max(0, page) * limit);
        return filtered.subList(from, Math.min(filtered.size(), from + limit));
    }

    @Override
    public Map<String, Object> stats() {
        List<NotificationView> notifications = scan(notificationsTable).stream().map(this::notification).toList();
        long start = Instant.now().truncatedTo(ChronoUnit.DAYS).toEpochMilli();
        long today = notifications.stream().filter(value -> value.requestedAt() != null && value.requestedAt().toEpochMilli() >= start).count();
        long sent = notifications.stream().filter(value -> "DELIVERED".equals(value.status()) || "PARTIALLY_DELIVERED".equals(value.status())).count();
        long failed = notifications.stream().filter(value -> "FAILED".equals(value.status())).count();
        long retries = scan(attemptsTable).stream().filter(item -> integer(item, "attempt") > 1).count();
        long completed = sent + failed;
        return Map.of("totalNotificationsToday", today, "sentCount", sent, "failedCount", failed,
                "pendingOutboxCount", 0, "retryCount", retries, "dlqCount", 0,
                "providerErrorRate", completed == 0 ? 0.0 : (double) failed / completed, "throughputPerMinute", 0.0);
    }

    @Override
    public void clearForRebuild() {
        clear(notificationsTable, "notificationId");
        clear(deliveriesTable, "deliveryId");
        clear(attemptsTable, "eventId");
        clear(processedTable, "consumerEventId");
    }

    private boolean transact(String consumer, String eventId, List<TransactWriteItem> operations) {
        Map<String, AttributeValue> marker = Map.of("consumerEventId", s(consumer + "#" + eventId),
                "processedAtEpoch", n(Instant.now().toEpochMilli()), "expiresAt", n(ttl(Instant.now())));
        Put markerPut = Put.builder().tableName(processedTable).item(marker)
                .conditionExpression("attribute_not_exists(consumerEventId)").build();
        List<TransactWriteItem> items = new ArrayList<>();
        items.add(TransactWriteItem.builder().put(markerPut).build());
        items.addAll(operations);
        try {
            dynamo.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(items).build());
            return true;
        } catch (TransactionCanceledException exception) {
            if (isProcessed(consumer, eventId)) return false;
            throw exception;
        }
    }

    private boolean isProcessed(String consumer, String eventId) {
        return !dynamo.getItem(GetItemRequest.builder().tableName(processedTable)
                .key(key("consumerEventId", consumer + "#" + eventId)).consistentRead(true).build()).item().isEmpty();
    }

    private UpdateItemRequest deliveryUpdate(DeliveryResult event) {
        Update model = deliveryUpdateModel(event);
        return UpdateItemRequest.builder().tableName(model.tableName()).key(model.key())
                .updateExpression(model.updateExpression()).expressionAttributeNames(model.expressionAttributeNames())
                .expressionAttributeValues(model.expressionAttributeValues()).build();
    }

    private Update deliveryUpdateModel(DeliveryResult event) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":notification", s(event.notificationId()));
        values.put(":tenant", s(event.tenantId()));
        values.put(":channel", s(event.channel()));
        values.put(":status", s(event.status()));
        values.put(":attempt", n(event.attempt()));
        values.put(":provider", nullable(event.providerMessageId()));
        values.put(":code", nullable(event.errorCode()));
        values.put(":message", nullable(event.errorMessage()));
        values.put(":updated", n(epoch(event.occurredAt())));
        values.put(":ttl", n(ttl(event.occurredAt())));
        return Update.builder().tableName(deliveriesTable).key(key("deliveryId", event.deliveryId()))
                .updateExpression("SET notificationId=:notification,tenantId=:tenant,channel=:channel,#status=:status," +
                        "attempt=:attempt,providerMessageId=:provider,errorCode=:code,errorMessage=:message," +
                        "updatedAtEpoch=:updated,expiresAt=:ttl")
                .expressionAttributeNames(Map.of("#status", "status")).expressionAttributeValues(values).build();
    }

    private void aggregate(String notificationId, Instant occurredAt) {
        List<DeliveryView> deliveries = scan(deliveriesTable, true).stream().map(this::delivery)
                .filter(value -> notificationId.equals(value.notificationId())).toList();
        if (deliveries.isEmpty()) return;
        long delivered = deliveries.stream().filter(value -> "DELIVERED".equals(value.status()) || "SENT".equals(value.status())).count();
        long failed = deliveries.stream().filter(value -> "FAILED".equals(value.status())).count();
        String status = delivered == deliveries.size() ? "DELIVERED" : failed == deliveries.size() ? "FAILED"
                : delivered > 0 && failed > 0 ? "PARTIALLY_DELIVERED" : "PROCESSING";
        dynamo.updateItem(UpdateItemRequest.builder().tableName(notificationsTable).key(key("notificationId", notificationId))
                .updateExpression("SET #status=:status,updatedAtEpoch=:updated")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(":status", s(status), ":updated", n(epoch(occurredAt)))).build());
    }

    private List<Map<String, AttributeValue>> queryTenant(String tenantId) {
        return query(QueryRequest.builder().tableName(notificationsTable).indexName(TENANT_TIME_INDEX)
                .keyConditionExpression("tenantId=:tenant").expressionAttributeValues(Map.of(":tenant", s(tenantId))).build());
    }

    private List<Map<String, AttributeValue>> queryDeliveries(String notificationId) {
        return query(QueryRequest.builder().tableName(deliveriesTable).indexName(NOTIFICATION_TIME_INDEX)
                .keyConditionExpression("notificationId=:notification")
                .expressionAttributeValues(Map.of(":notification", s(notificationId))).build());
    }

    private List<Map<String, AttributeValue>> queryUser(String tenantId,String userId) {
        return query(QueryRequest.builder().tableName(notificationsTable).indexName(USER_TIME_INDEX)
                .keyConditionExpression("tenantUserKey=:user")
                .expressionAttributeValues(Map.of(":user",s(tenantId+"#"+userId))).build());
    }

    private List<Map<String, AttributeValue>> query(QueryRequest request) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        Map<String, AttributeValue> start = null;
        do {
            var builder = request.toBuilder();
            if (start != null && !start.isEmpty()) builder.exclusiveStartKey(start);
            QueryRequest page = builder.build();
            var response = dynamo.query(page);
            items.addAll(response.items());
            start = response.lastEvaluatedKey();
        } while (start != null && !start.isEmpty());
        return items;
    }

    private List<Map<String, AttributeValue>> scan(String table) {
        return scan(table, false);
    }

    private List<Map<String, AttributeValue>> scan(String table, boolean consistentRead) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        Map<String, AttributeValue> start = null;
        do {
            var builder = ScanRequest.builder().tableName(table).consistentRead(consistentRead);
            if (start != null && !start.isEmpty()) builder.exclusiveStartKey(start);
            var response = dynamo.scan(builder.build());
            items.addAll(response.items());
            start = response.lastEvaluatedKey();
        } while (start != null && !start.isEmpty());
        return items;
    }

    private void clear(String table, String keyName) {
        List<Map<String, AttributeValue>> items = scan(table);
        for (int offset = 0; offset < items.size(); offset += 25) {
            List<WriteRequest> writes = items.subList(offset, Math.min(items.size(), offset + 25)).stream()
                    .map(item -> WriteRequest.builder().deleteRequest(DeleteRequest.builder()
                            .key(Map.of(keyName, item.get(keyName))).build()).build()).toList();
            Map<String,List<WriteRequest>> pending=Map.of(table,writes);
            for(int attempt=0;attempt<10&&!pending.isEmpty();attempt++){
                pending=dynamo.batchWriteItem(BatchWriteItemRequest.builder().requestItems(pending).build()).unprocessedItems();
            }
            if(!pending.isEmpty())throw new IllegalStateException("DynamoDB did not clear all projection items from "+table);
        }
    }

    private NotificationView notification(Map<String, AttributeValue> item) {
        String id = string(item, "notificationId");
        String template = string(item, "templateId");
        Instant requested = instant(item, "requestedAtEpoch");
        List<AttributeValue> channels = item.getOrDefault("requestedChannels", AttributeValue.builder().l(List.of()).build()).l();
        String channel = channels == null || channels.isEmpty() ? null : channels.get(0).s();
        return new NotificationView(id, id, string(item, "requestId"), string(item, "tenantId"), string(item, "productId"),
                string(item, "userId"), template, template, channel, string(item, "status"), string(item, "reasonCode"),
                string(item, "reasonMessage"), requested, requested, instant(item, "updatedAtEpoch"));
    }

    private DeliveryView delivery(Map<String, AttributeValue> item) {
        String id = string(item, "deliveryId");
        String notification = string(item, "notificationId");
        String channel = string(item, "channel");
        int attempt = integer(item, "attempt");
        return new DeliveryView(id, id, notification, notification, channel,
                channel == null ? null : channel.toLowerCase(), null, string(item, "status"), attempt, attempt,
                string(item, "providerMessageId"), string(item, "errorCode"), string(item, "errorMessage"),
                instant(item, "updatedAtEpoch"));
    }

    private static Map<String, AttributeValue> key(String name, String value) { return Map.of(name, s(value)); }
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value == null ? "" : value).build(); }
    private static AttributeValue n(long value) { return AttributeValue.builder().n(Long.toString(value)).build(); }
    private static AttributeValue nullable(String value) { return value == null ? AttributeValue.builder().nul(true).build() : s(value); }
    private static long epoch(Instant value) { return (value == null ? Instant.now() : value).toEpochMilli(); }
    private long ttl(Instant value) { return (value == null ? Instant.now() : value).plus(retentionDays, ChronoUnit.DAYS).getEpochSecond(); }
    private static String string(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name); return value == null || Boolean.TRUE.equals(value.nul()) ? null : value.s();
    }
    private static int integer(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name); return value == null || value.n() == null ? 0 : Integer.parseInt(value.n());
    }
    private static Instant instant(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name); return value == null || value.n() == null ? null : Instant.ofEpochMilli(Long.parseLong(value.n()));
    }
}
