package com.notificationplatform.adminbff;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
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

@SpringBootApplication
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
                            return new DashboardStats(0, 0, 0, 0, 0.0);
                        }
                        return new DashboardStats(
                                stats.totalNotificationsToday(),
                                stats.deliveredCount(),
                                stats.failedCount(),
                                stats.retryAttemptCount(),
                                stats.providerErrorRate());
                    });
        }

        @GetMapping("/notifications/page")
        Object notificationsPage(
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "50") int size,
                @RequestParam(required = false) String productId,
                @RequestParam(required = false) String status,
                @RequestParam(required = false) String channel) {
            return downstreamRequest("notification-projection-service", () -> downstream.projection.get()
                    .uri(uri -> uri.path("/projections/notifications")
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .queryParamIfPresent("productId", java.util.Optional.ofNullable(productId))
                            .queryParamIfPresent("status", java.util.Optional.ofNullable(status))
                            .queryParamIfPresent("channel", java.util.Optional.ofNullable(channel))
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

        @GetMapping("/deliveries/page")
        Object deliveriesPage(
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "50") int size,
                @RequestParam(required = false) UUID notificationRequestId,
                @RequestParam(required = false) String status,
                @RequestParam(required = false) String channel) {
            return downstreamRequest("notification-projection-service", () -> downstream.projection.get()
                    .uri(uri -> uri.path("/projections/deliveries")
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .queryParamIfPresent("notificationId", java.util.Optional.ofNullable(notificationRequestId))
                            .queryParamIfPresent("status", java.util.Optional.ofNullable(status))
                            .queryParamIfPresent("channel", java.util.Optional.ofNullable(channel))
                            .build())
                    .retrieve()
                    .body(Object.class));
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

    }

    @org.springframework.stereotype.Component
    static class Downstream {
        final RestClient notificationApi;
        final RestClient projection;
        final RestClient template;
        final RestClient preference;
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
                @Value("${EMAIL_WORKER_SERVICE_URL:http://localhost:8085}") String emailUrl,
                @Value("${SMS_WORKER_SERVICE_URL:http://localhost:8086}") String smsUrl,
                @Value("${PUSH_WORKER_SERVICE_URL:http://localhost:8087}") String pushUrl,
                @Value("${IN_APP_WORKER_SERVICE_URL:http://localhost:8089}") String inAppUrl,
                @Value("${WEBHOOK_WORKER_SERVICE_URL:http://localhost:8090}") String webhookUrl) {
            this.notificationApi = builder.clone().baseUrl(notificationApiUrl).build();
            this.projection = builder.clone().baseUrl(projectionUrl).build();
            this.template = builder.clone().baseUrl(templateUrl).build();
            this.preference = builder.clone().baseUrl(preferenceUrl).build();
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
            long deliveredCount,
            long failedCount,
            long retryAttemptCount,
            double providerErrorRate) {
    }

    record NotificationStats(
            long totalNotificationsToday,
            long deliveredCount,
            long failedCount,
            long retryAttemptCount,
            double providerErrorRate) {
    }
}
