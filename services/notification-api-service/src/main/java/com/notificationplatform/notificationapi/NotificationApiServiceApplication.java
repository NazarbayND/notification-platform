package com.notificationplatform.notificationapi;

import com.notificationplatform.common.observability.CorrelationIds;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NotificationApiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApiServiceApplication.class, args);
    }

    @RestController
    static class HealthController {
        @GetMapping({"/health/live", "/health/ready"})
        Health health() {
            return new Health("UP", Instant.now());
        }
    }

    @RestController
    @RequestMapping("/notifications")
    static class NotificationController {
        private final NotificationIntakeService intakeService;
        private final NotificationStatusLookupService statusLookupService;
        private final NotificationProjectionClient projectionClient;

        NotificationController(
                NotificationIntakeService intakeService,
                NotificationStatusLookupService statusLookupService,
                NotificationProjectionClient projectionClient) {
            this.intakeService = intakeService;
            this.statusLookupService = statusLookupService;
            this.projectionClient = projectionClient;
        }

        @PostMapping
        ResponseEntity<NotificationAccepted> submit(
                @Valid @RequestBody NotificationRequest request,
                @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
            String resolvedCorrelationId = correlationId == null || correlationId.isBlank()
                    ? CorrelationIds.current()
                    : correlationId;
            NotificationAccepted response = intakeService.submit(request, resolvedCorrelationId);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        }

        @GetMapping("/{notificationId}/status")
        NotificationStatus status(@PathVariable UUID notificationId) {
            return statusLookupService.find(notificationId);
        }

        @GetMapping("/stats")
        Object stats() {
            return projectionClient.stats();
        }

        @GetMapping("/page")
        Object page(
                @RequestParam(required = false) String productId,
                @RequestParam(required = false) String status,
                @RequestParam(required = false) String channel,
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "50") int size) {
            return projectionClient.notifications(productId, status, channel, page, size, false);
        }

        @GetMapping("/deliveries/page")
        Object deliveriesPage(
                @RequestParam(required = false) UUID notificationId,
                @RequestParam(required = false) String status,
                @RequestParam(required = false) String channel,
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "50") int size) {
            return projectionClient.deliveries(notificationId, status, channel, page, size);
        }

        @GetMapping("/{notificationId}")
        Object get(@PathVariable UUID notificationId) {
            return projectionClient.notification(notificationId);
        }

        @GetMapping
        Object list(
                @RequestParam(required = false) String status,
                @RequestParam(required = false) String channel,
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "50") int size) {
            return projectionClient.notifications(null, status, channel, page, size, true);
        }
    }

    record Health(String status, Instant checkedAt) {
    }

    record NotificationRequest(
            @NotBlank String userId,
            @NotBlank String productId,
            @NotBlank @Pattern(regexp = "(?i)EMAIL|SMS|PUSH|IN_APP|WEBHOOK") String channel,
            @NotBlank String templateKey,
            Map<String, Object> variables,
            @NotBlank @Size(max = 200) String idempotencyKey,
            @NotBlank @Size(max = 512) String destination,
            @Size(max = 160) String tenantId,
            UUID notificationId) {
    }

    record NotificationAccepted(
            UUID notificationId,
            UUID requestId,
            String status,
            Instant acceptedAt,
            String correlationId,
            String channel) {
    }

    record NotificationStatus(UUID notificationId, String status, String channel, Instant updatedAt) {
    }
}
