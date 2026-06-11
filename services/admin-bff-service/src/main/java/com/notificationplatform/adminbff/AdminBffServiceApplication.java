package com.notificationplatform.adminbff;

import java.time.Instant;
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

        AdminController(Downstream downstream) {
            this.downstream = downstream;
        }

        @GetMapping({"/dashboard", "/dashboard/stats"})
        DashboardStats dashboard() {
            Object[] notifications = downstream.notificationApi.get()
                    .uri("/notifications?size=200")
                    .retrieve()
                    .body(Object[].class);
            Object[] outbox = downstream.outboxPublisher.get()
                    .uri("/outbox/events")
                    .retrieve()
                    .body(Object[].class);
            long totalNotifications = notifications == null ? 0 : notifications.length;
            long pendingOutbox = countByStatus(outbox, "PENDING");
            long failedOutbox = countByStatus(outbox, "FAILED") + countByStatus(outbox, "DEAD_LETTER");
            return new DashboardStats(totalNotifications, 0, failedOutbox, pendingOutbox, failedOutbox, countByStatus(outbox, "DEAD_LETTER"), 0.0, 0.0);
        }

        @GetMapping("/notifications")
        Object notifications(
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "50") int size,
                @RequestParam(required = false) String status,
                @RequestParam(required = false) String channel) {
            return downstream.notificationApi.get()
                    .uri(uri -> uri.path("/notifications")
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .queryParamIfPresent("status", java.util.Optional.ofNullable(status))
                            .queryParamIfPresent("channel", java.util.Optional.ofNullable(channel))
                            .build())
                    .retrieve()
                    .body(Object.class);
        }

        @GetMapping("/notifications/{id}")
        Object notification(@PathVariable UUID id) {
            return downstream.notificationApi.get().uri("/notifications/{id}", id).retrieve().body(Object.class);
        }

        @PostMapping("/notifications")
        Object createNotification(@RequestBody Map<String, Object> request) {
            return downstream.notificationApi.post().uri("/notifications").body(request).retrieve().body(Object.class);
        }

        @GetMapping("/notifications/{id}/deliveries")
        Object notificationDeliveries(@PathVariable UUID id) {
            return outboxEventsAsDeliveries(id);
        }

        @GetMapping("/deliveries")
        Object deliveries() {
            return outboxEventsAsDeliveries(null);
        }

        @GetMapping("/outbox-events")
        Object outboxEvents() {
            return downstream.outboxPublisher.get().uri("/outbox/events").retrieve().body(Object.class);
        }

        @PostMapping("/outbox-events/{id}/retry")
        Object retryOutboxEvent(@PathVariable UUID id) {
            return downstream.outboxPublisher.post().uri("/outbox/events/{id}/retry", id).retrieve().body(Object.class);
        }

        @GetMapping("/templates")
        Object templates() {
            return downstream.template.get().uri("/templates").retrieve().body(Object.class);
        }

        @PostMapping("/templates")
        Object createTemplate(@RequestBody Map<String, Object> request) {
            return downstream.template.post().uri("/templates").body(request).retrieve().body(Object.class);
        }

        @PutMapping("/templates/{id}")
        Object updateTemplate(@PathVariable UUID id, @RequestBody Map<String, Object> request) {
            return downstream.template.put().uri("/templates/{id}", id).body(request).retrieve().body(Object.class);
        }

        @PostMapping("/templates/{id}/preview")
        Object previewTemplate(@PathVariable UUID id, @RequestBody Map<String, Object> request) {
            return downstream.template.post().uri("/templates/{id}/preview", id).body(request).retrieve().body(Object.class);
        }

        @GetMapping("/preferences")
        Object preferences(
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "50") int size,
                @RequestParam(required = false) String productId,
                @RequestParam(required = false) String userId) {
            return downstream.preference.get()
                    .uri(uri -> uri.path("/preferences")
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .queryParamIfPresent("productId", java.util.Optional.ofNullable(productId))
                            .queryParamIfPresent("userId", java.util.Optional.ofNullable(userId))
                            .build())
                    .retrieve()
                    .body(Object.class);
        }

        @PutMapping("/preferences/{id}")
        Object updatePreference(@PathVariable UUID id, @RequestBody Map<String, Object> request) {
            return downstream.preference.put().uri("/preferences/{id}", id).body(request).retrieve().body(Object.class);
        }

        @GetMapping("/test/email-messages")
        Object emailMessages() {
            return downstream.email.get().uri("/test/email-messages").retrieve().body(Object.class);
        }

        @GetMapping("/test/sms-messages")
        Object smsMessages() {
            return downstream.sms.get().uri("/test/sms-messages").retrieve().body(Object.class);
        }

        @GetMapping("/test/push-messages")
        Object pushMessages() {
            return downstream.push.get().uri("/test/push-messages").retrieve().body(Object.class);
        }

        @GetMapping("/test/in-app-notifications")
        Object inAppNotifications() {
            return downstream.inApp.get().uri("/test/in-app-notifications").retrieve().body(Object.class);
        }

        @GetMapping("/test/webhook-requests")
        Object webhookRequests() {
            return downstream.webhook.get().uri("/received-webhooks").retrieve().body(Object.class);
        }

        private long countByStatus(Object[] items, String status) {
            if (items == null) {
                return 0;
            }
            return List.of(items).stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .filter(item -> status.equals(item.get("status")))
                    .count();
        }

        private List<Map<String, Object>> outboxEventsAsDeliveries(UUID notificationId) {
            Object[] events = downstream.outboxPublisher.get().uri("/outbox/events").retrieve().body(Object[].class);
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

        private Map<String, Object> outboxEventAsDelivery(Map<?, ?> event) {
            Object payloadValue = event.get("payload");
            Map<?, ?> payload = payloadValue instanceof Map<?, ?> map ? map : Map.of();
            String status = String.valueOf(event.get("status"));
            Map<String, Object> delivery = new java.util.LinkedHashMap<>();
            delivery.put("id", String.valueOf(event.get("eventId")));
            delivery.put("notificationRequestId", String.valueOf(event.get("aggregateId")));
            delivery.put("templateId", "");
            delivery.put("channel", String.valueOf(valueOrDefault(payload, "channel", "EMAIL")));
            delivery.put("status", "PUBLISHED".equals(status) ? "SENT" : status);
            delivery.put("provider", "outbox");
            delivery.put("destination", String.valueOf(valueOrDefault(payload, "destination", "")));
            delivery.put("attemptCount", valueOrDefault(event, "attemptCount", 0));
            delivery.put("maxAttempts", valueOrDefault(event, "maxAttempts", 0));
            delivery.put("nextAttemptAt", event.get("nextAttemptAt"));
            delivery.put("lastErrorMessage", event.get("lastError"));
            delivery.put("createdAt", event.get("createdAt"));
            return delivery;
        }

        private Object valueOrDefault(Map<?, ?> source, String key, Object fallback) {
            Object value = source.get(key);
            return value == null ? fallback : value;
        }
    }

    @org.springframework.stereotype.Component
    static class Downstream {
        final RestClient notificationApi;
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
                @Value("${TEMPLATE_SERVICE_URL:http://localhost:8082}") String templateUrl,
                @Value("${PREFERENCE_SERVICE_URL:http://localhost:8083}") String preferenceUrl,
                @Value("${OUTBOX_PUBLISHER_SERVICE_URL:http://localhost:8084}") String outboxUrl,
                @Value("${EMAIL_WORKER_SERVICE_URL:http://localhost:8085}") String emailUrl,
                @Value("${SMS_WORKER_SERVICE_URL:http://localhost:8086}") String smsUrl,
                @Value("${PUSH_WORKER_SERVICE_URL:http://localhost:8087}") String pushUrl,
                @Value("${IN_APP_WORKER_SERVICE_URL:http://localhost:8089}") String inAppUrl,
                @Value("${WEBHOOK_WORKER_SERVICE_URL:http://localhost:8090}") String webhookUrl) {
            this.notificationApi = builder.clone().baseUrl(notificationApiUrl).build();
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
}
