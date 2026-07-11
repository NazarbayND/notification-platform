package com.notificationplatform.adminbff;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class AdminBffServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminBffServiceApplication.class, args);
    }

    @RestController
    static class HealthController {
        @GetMapping({"/health/live", "/health/ready"})
        Health health() {
            return new Health("UP", Instant.now());
        }
    }

    @RestController
    @RequestMapping("/admin")
    static class AdminController {
        private final Downstream downstream;
        private final MeterRegistry meterRegistry;

        AdminController(Downstream downstream, MeterRegistry meterRegistry) {
            this.downstream = downstream;
            this.meterRegistry = meterRegistry;
        }

        @GetMapping({"/dashboard", "/dashboard/stats"})
        DashboardStats dashboard() {
            return Timer.builder("admin_bff_request_duration_seconds")
                    .tag("endpoint", "dashboard")
                    .register(meterRegistry)
                    .record(() -> {
                        NotificationStats stats = downstreamRequest("notification-projection-service", () -> downstream.projection.get()
                                .uri("/projections/notifications/stats")
                                .retrieve()
                                .body(NotificationStats.class));
                        if (stats == null) {
                            return new DashboardStats(0, 0, 0, 0, 0, 0, 0.0, 0.0);
                        }
                        return new DashboardStats(
                                stats.totalNotificationsToday(),
                                stats.sentCount(),
                                stats.failedCount(),
                                stats.pendingOutboxCount(),
                                stats.retryCount(),
                                stats.dlqCount(),
                                stats.providerErrorRate(),
                                stats.throughputPerMinute());
                    });
        }

        @GetMapping("/notifications")
        List<Map<String, Object>> notifications(
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "50") int size,
                @RequestParam(required = false) String productId,
                @RequestParam(required = false) String status,
                @RequestParam(required = false) String channel,
                @RequestParam(required = false) String priority,
                @RequestParam(required = false) String dateFrom,
                @RequestParam(required = false) String dateTo) {
            Object response = downstreamRequest("notification-projection-service", () -> downstream.projection.get()
                    .uri(uri -> uri.path("/projections/notifications")
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .queryParamIfPresent("status", java.util.Optional.ofNullable(status))
                            .queryParamIfPresent("channel", java.util.Optional.ofNullable(channel))
                            .build())
                    .retrieve()
                    .body(Object.class));
            Object notifications = response instanceof Map<?, ?> pageResult ? pageResult.get("items") : response;
            return objectList(notifications).stream()
                    .filter(item -> matches(item, "productId", productId))
                    .filter(item -> matches(item, "priority", priority))
                    .filter(item -> createdOnOrAfter(item, dateFrom))
                    .filter(item -> createdOnOrBefore(item, dateTo))
                    .toList();
        }

        @GetMapping("/notifications/page")
        Object notificationsPage(
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "50") int size,
                @RequestParam(required = false) String productId,
                @RequestParam(required = false) String status,
                @RequestParam(required = false) String channel,
                @RequestParam(required = false) String priority,
                @RequestParam(required = false) String dateFrom,
                @RequestParam(required = false) String dateTo) {
            return downstreamRequest("notification-projection-service", () -> downstream.projection.get()
                    .uri(uri -> uri.path("/projections/notifications")
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .queryParamIfPresent("productId", java.util.Optional.ofNullable(productId))
                            .queryParamIfPresent("status", java.util.Optional.ofNullable(status))
                            .queryParamIfPresent("channel", java.util.Optional.ofNullable(channel))
                            .queryParamIfPresent("priority", java.util.Optional.ofNullable(priority))
                            .queryParamIfPresent("dateFrom", java.util.Optional.ofNullable(dateFrom))
                            .queryParamIfPresent("dateTo", java.util.Optional.ofNullable(dateTo))
                            .build())
                    .retrieve()
                    .body(Object.class));
        }

        @GetMapping("/notifications/{id}")
        Object notification(@PathVariable UUID id) {
            return downstreamRequest("notification-projection-service", () -> downstream.projection.get().uri("/projections/notifications/{id}", id).retrieve().body(Object.class));
        }

        @PostMapping("/notifications")
        Object createNotification(@RequestBody Map<String, Object> request) {
            return downstreamRequest("notification-api-service", () -> downstream.notificationApi.post().uri("/notifications").body(request).retrieve().body(Object.class));
        }

        @GetMapping("/notifications/{id}/deliveries")
        Object notificationDeliveries(@PathVariable UUID id) {
            return downstreamRequest("notification-projection-service", () -> downstream.projection.get()
                    .uri("/projections/notifications/{id}/deliveries", id).retrieve().body(Object.class));
        }

        @GetMapping("/deliveries")
        Object deliveries(
                @RequestParam(required = false) String status,
                @RequestParam(required = false) String channel,
                @RequestParam(required = false) String provider) {
            return projectionDeliveries().stream()
                    .filter(item -> matches(item, "status", status))
                    .filter(item -> matches(item, "channel", channel))
                    .filter(item -> matches(item, "provider", provider))
                    .toList();
        }

        @GetMapping("/deliveries/page")
        Object deliveriesPage(
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "50") int size,
                @RequestParam(required = false) UUID notificationRequestId,
                @RequestParam(required = false) String status,
                @RequestParam(required = false) String channel,
                @RequestParam(required = false) String provider) {
            return downstreamRequest("notification-projection-service", () -> downstream.projection.get()
                    .uri(uri -> uri.path("/projections/deliveries")
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .queryParamIfPresent("notificationId", java.util.Optional.ofNullable(notificationRequestId))
                            .queryParamIfPresent("status", java.util.Optional.ofNullable(status))
                            .queryParamIfPresent("channel", java.util.Optional.ofNullable(channel))
                            .queryParamIfPresent("provider", java.util.Optional.ofNullable(provider))
                            .build())
                    .retrieve()
                    .body(Object.class));
        }

        @GetMapping("/outbox-events")
        Object outboxEvents() {
            return downstreamRequest("outbox-publisher-service", () -> downstream.outboxPublisher.get().uri("/outbox/events").retrieve().body(Object.class));
        }

        @PostMapping("/outbox-events/{id}/retry")
        Object retryOutboxEvent(@PathVariable UUID id) {
            return downstreamRequest("outbox-publisher-service", () -> downstream.outboxPublisher.post().uri("/outbox/events/{id}/retry", id).retrieve().body(Object.class));
        }

        @GetMapping("/templates")
        List<Map<String, Object>> templates(
                @RequestParam(required = false) String productId,
                @RequestParam(required = false) String channel,
                @RequestParam(required = false) String status) {
            Object[] templates = downstreamRequest("template-service", () -> downstream.template.get().uri("/templates").retrieve().body(Object[].class));
            return objectList(templates).stream()
                    .filter(item -> matches(item, "productId", productId))
                    .filter(item -> matches(item, "channel", channel))
                    .filter(item -> matches(item, "status", status))
                    .toList();
        }

        @GetMapping("/products")
        Object products() {
            return downstreamRequest("template-service", () -> downstream.template.get().uri("/products").retrieve().body(Object.class));
        }

        @PostMapping("/products")
        Object createProduct(@RequestBody Map<String, Object> request) {
            return downstreamRequest("template-service", () -> downstream.template.post().uri("/products").body(request).retrieve().body(Object.class));
        }

        @PutMapping("/products/{id}")
        Object updateProduct(@PathVariable String id, @RequestBody Map<String, Object> request) {
            return downstreamRequest("template-service", () -> downstream.template.put().uri("/products/{id}", id).body(request).retrieve().body(Object.class));
        }

        @PostMapping("/templates")
        Object createTemplate(@RequestBody Map<String, Object> request) {
            return downstreamRequest("template-service", () -> downstream.template.post().uri("/templates").body(request).retrieve().body(Object.class));
        }

        @PutMapping("/templates/{id}")
        Object updateTemplate(@PathVariable UUID id, @RequestBody Map<String, Object> request) {
            return downstreamRequest("template-service", () -> downstream.template.put().uri("/templates/{id}", id).body(request).retrieve().body(Object.class));
        }

        @PostMapping("/templates/{id}/preview")
        Object previewTemplate(@PathVariable UUID id, @RequestBody Map<String, Object> request) {
            return downstreamRequest("template-service", () -> downstream.template.post().uri("/templates/{id}/preview", id).body(request).retrieve().body(Object.class));
        }

        @GetMapping("/preferences")
        Object preferences(
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "50") int size,
                @RequestParam(required = false) String productId,
                @RequestParam(required = false) String userId) {
            return downstreamRequest("preference-service", () -> downstream.preference.get()
                    .uri(uri -> uri.path("/preferences")
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .queryParamIfPresent("productId", java.util.Optional.ofNullable(productId))
                            .queryParamIfPresent("userId", java.util.Optional.ofNullable(userId))
                            .build())
                    .retrieve()
                    .body(Object.class));
        }

        @PutMapping("/preferences/{id}")
        Object updatePreference(@PathVariable UUID id, @RequestBody Map<String, Object> request) {
            return downstreamRequest("preference-service", () -> downstream.preference.put().uri("/preferences/{id}", id).body(request).retrieve().body(Object.class));
        }

        @GetMapping("/test/email-messages")
        Object emailMessages() {
            return downstreamRequest("email-worker-service", () -> downstream.email.get().uri("/test/email-messages").retrieve().body(Object.class));
        }

        @GetMapping("/test/sms-messages")
        Object smsMessages() {
            return downstreamRequest("sms-worker-service", () -> downstream.sms.get().uri("/test/sms-messages").retrieve().body(Object.class));
        }

        @GetMapping("/test/push-messages")
        Object pushMessages() {
            return downstreamRequest("push-worker-service", () -> downstream.push.get().uri("/test/push-messages").retrieve().body(Object.class));
        }

        @GetMapping("/test/in-app-notifications")
        Object inAppNotifications() {
            return downstreamRequest("in-app-worker-service", () -> downstream.inApp.get().uri("/test/in-app-notifications").retrieve().body(Object.class));
        }

        @GetMapping("/test/webhook-requests")
        Object webhookRequests() {
            return downstreamRequest("webhook-worker-service", () -> downstream.webhook.get().uri("/received-webhooks").retrieve().body(Object.class));
        }

        private <T> T downstreamRequest(String service, java.util.function.Supplier<T> call) {
            try {
                return call.get();
            } catch (RestClientResponseException exception) {
                meterRegistry.counter("admin_bff_downstream_error_total", "service", service, "reason", exception.getClass().getSimpleName()).increment();
                throw new ResponseStatusException(exception.getStatusCode(), downstreamErrorMessage(exception), exception);
            } catch (RestClientException exception) {
                meterRegistry.counter("admin_bff_downstream_error_total", "service", service, "reason", exception.getClass().getSimpleName()).increment();
                throw exception;
            }
        }

        private String downstreamErrorMessage(RestClientResponseException exception) {
            String body = exception.getResponseBodyAsString();
            if (body == null || body.isBlank()) {
                return exception.getStatusText();
            }
            return body.length() > 500 ? body.substring(0, 500) : body;
        }

        private long countByStatus(List<Map<String, Object>> items, String status) {
            return items.stream()
                    .filter(item -> status.equals(String.valueOf(item.get("status"))))
                    .count();
        }

        private long countByAnyStatus(List<Map<String, Object>> items, String... statuses) {
            return items.stream()
                    .filter(item -> {
                        String itemStatus = String.valueOf(item.get("status"));
                        for (String status : statuses) {
                            if (status.equals(itemStatus)) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .count();
        }

        private long countCreatedOnOrAfter(List<Map<String, Object>> items, Instant start) {
            return items.stream()
                    .filter(item -> {
                        Instant createdAt = instantValue(item, "createdAt");
                        return createdAt != null && !createdAt.isBefore(start);
                    })
                    .count();
        }

        private List<Map<String, Object>> outboxEventsAsDeliveries(UUID notificationId) {
            Object[] events = downstreamRequest("outbox-publisher-service", () -> downstream.outboxPublisher.get().uri("/outbox/events").retrieve().body(Object[].class));
            if (events == null) {
                return List.of();
            }
            return List.of(events).stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .filter(event -> notificationId == null || notificationId.toString().equals(event.get("aggregateId")))
                    .map(this::outboxEventAsDelivery)
                    .toList();
        }

        private List<Map<String, Object>> projectionDeliveries() {
            Object[] deliveries = downstreamRequest("notification-projection-service", () -> downstream.projection.get()
                    .uri("/projections/deliveries").retrieve().body(Object[].class));
            return objectList(deliveries);
        }

        private Map<String, Object> outboxEventAsDelivery(Map<?, ?> event) {
            Object payloadValue = event.get("payload");
            Map<?, ?> payload = payloadValue instanceof Map<?, ?> map ? map : Map.of();
            String status = String.valueOf(event.get("status"));
            Map<String, Object> delivery = new java.util.LinkedHashMap<>();
            delivery.put("id", String.valueOf(event.get("eventId")));
            delivery.put("notificationRequestId", String.valueOf(event.get("aggregateId")));
            delivery.put("templateId", "");
            delivery.put("channel", String.valueOf(valueOrDefault(payload, "channel", "EMAIL")));
            delivery.put("status", deliveryStatus(status));
            delivery.put("provider", "outbox");
            delivery.put("destination", String.valueOf(valueOrDefault(payload, "destination", "")));
            delivery.put("attemptCount", valueOrDefault(event, "attemptCount", 0));
            delivery.put("maxAttempts", valueOrDefault(event, "maxAttempts", 0));
            delivery.put("nextAttemptAt", event.get("nextAttemptAt"));
            delivery.put("lastErrorMessage", event.get("lastError"));
            delivery.put("createdAt", event.get("createdAt"));
            return delivery;
        }

        private String deliveryStatus(String outboxStatus) {
            return switch (outboxStatus) {
                case "PUBLISHED" -> "SENT";
                case "DEAD_LETTER" -> "DEAD_LETTERED";
                default -> outboxStatus;
            };
        }

        private List<Map<String, Object>> objectList(Object value) {
            if (value == null) {
                return List.of();
            }
            List<?> rawItems;
            if (value instanceof Object[] array) {
                rawItems = List.of(array);
            } else if (value instanceof List<?> list) {
                rawItems = list;
            } else {
                return List.of();
            }
            List<Map<String, Object>> items = new ArrayList<>();
            for (Object rawItem : rawItems) {
                if (rawItem instanceof Map<?, ?> map) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    map.forEach((key, itemValue) -> normalized.put(String.valueOf(key), itemValue));
                    items.add(normalized);
                }
            }
            return items;
        }

        private boolean matches(Map<String, Object> item, String key, String expected) {
            if (expected == null || expected.isBlank()) {
                return true;
            }
            Object actual = item.get(key);
            return actual != null && expected.equalsIgnoreCase(String.valueOf(actual));
        }

        private boolean createdOnOrAfter(Map<String, Object> item, String date) {
            if (date == null || date.isBlank()) {
                return true;
            }
            return itemDate(item).compareTo(date) >= 0;
        }

        private boolean createdOnOrBefore(Map<String, Object> item, String date) {
            if (date == null || date.isBlank()) {
                return true;
            }
            return itemDate(item).compareTo(date) <= 0;
        }

        private String itemDate(Map<String, Object> item) {
            Object createdAt = item.get("createdAt");
            String value = createdAt == null ? "" : String.valueOf(createdAt);
            return value.length() >= 10 ? value.substring(0, 10) : value;
        }

        private Instant instantValue(Map<String, Object> item, String key) {
            Object value = item.get(key);
            if (value instanceof Instant instant) {
                return instant;
            }
            if (value == null) {
                return null;
            }
            try {
                return Instant.parse(String.valueOf(value));
            } catch (RuntimeException exception) {
                return null;
            }
        }

        private Object valueOrDefault(Map<?, ?> source, String key, Object fallback) {
            Object value = source.get(key);
            return value == null ? fallback : value;
        }
    }

    @org.springframework.stereotype.Component
    static class Downstream {
        final RestClient notificationApi;
        final RestClient projection;
        final RestClient template;
        final RestClient preference;
        final RestClient outboxPublisher;
        final RestClient email;
        final RestClient sms;
        final RestClient push;
        final RestClient inApp;
        final RestClient webhook;

        Downstream(
                RestClient.Builder builder,
                @Value("${NOTIFICATION_API_URL:http://localhost:8081}") String notificationApiUrl,
                @Value("${NOTIFICATION_PROJECTION_URL:http://localhost:8092}") String projectionUrl,
                @Value("${TEMPLATE_SERVICE_URL:http://localhost:8082}") String templateUrl,
                @Value("${PREFERENCE_SERVICE_URL:http://localhost:8083}") String preferenceUrl,
                @Value("${OUTBOX_PUBLISHER_SERVICE_URL:http://localhost:8084}") String outboxUrl,
                @Value("${EMAIL_WORKER_SERVICE_URL:http://localhost:8085}") String emailUrl,
                @Value("${SMS_WORKER_SERVICE_URL:http://localhost:8086}") String smsUrl,
                @Value("${PUSH_WORKER_SERVICE_URL:http://localhost:8087}") String pushUrl,
                @Value("${IN_APP_WORKER_SERVICE_URL:http://localhost:8089}") String inAppUrl,
                @Value("${WEBHOOK_WORKER_SERVICE_URL:http://localhost:8090}") String webhookUrl) {
            this.notificationApi = builder.clone().baseUrl(notificationApiUrl).build();
            this.projection = builder.clone().baseUrl(projectionUrl).build();
            this.template = builder.clone().baseUrl(templateUrl).build();
            this.preference = builder.clone().baseUrl(preferenceUrl).build();
            this.outboxPublisher = builder.clone().baseUrl(outboxUrl).build();
            this.email = builder.clone().baseUrl(emailUrl).build();
            this.sms = builder.clone().baseUrl(smsUrl).build();
            this.push = builder.clone().baseUrl(pushUrl).build();
            this.inApp = builder.clone().baseUrl(inAppUrl).build();
            this.webhook = builder.clone().baseUrl(webhookUrl).build();
        }
    }

    record Health(String status, Instant checkedAt) {
    }

    record DashboardStats(
            long totalNotificationsToday,
            long sentCount,
            long failedCount,
            long pendingOutboxCount,
            long retryCount,
            long dlqCount,
            double providerErrorRate,
            double throughputPerMinute) {
    }

    record NotificationStats(
            long totalNotificationsToday,
            long sentCount,
            long failedCount,
            long pendingOutboxCount,
            long retryCount,
            long dlqCount,
            double providerErrorRate,
            double throughputPerMinute) {
    }
}
