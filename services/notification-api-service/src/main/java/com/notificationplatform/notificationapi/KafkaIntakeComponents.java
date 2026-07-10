package com.notificationplatform.notificationapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notificationplatform.contracts.NotificationRequested;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.kafka.clients.producer.BufferExhaustedException;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Validated
@ConfigurationProperties("notification.intake")
class NotificationIntakeProperties {
    @Min(1)
    private int globalRatePerSecond = 5000;
    @Min(1)
    private int perTenantRatePerSecond = 500;
    @Min(1)
    private int maxConcurrentRequests = 1000;
    @Min(1024)
    private long maxRequestBytes = 262_144;
    @Min(1)
    private int maxRecipientsPerRequest = 1000;
    @Min(1)
    private int maxChannelsPerRequest = 5;
    @NotNull
    private Duration kafkaPublishTimeout = Duration.ofSeconds(3);
    @NotNull
    private Duration acceptanceTtl = Duration.ofMinutes(20);

    public int getGlobalRatePerSecond() { return globalRatePerSecond; }
    public void setGlobalRatePerSecond(int value) { globalRatePerSecond = value; }
    public int getPerTenantRatePerSecond() { return perTenantRatePerSecond; }
    public void setPerTenantRatePerSecond(int value) { perTenantRatePerSecond = value; }
    public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
    public void setMaxConcurrentRequests(int value) { maxConcurrentRequests = value; }
    public long getMaxRequestBytes() { return maxRequestBytes; }
    public void setMaxRequestBytes(long value) { maxRequestBytes = value; }
    public int getMaxRecipientsPerRequest() { return maxRecipientsPerRequest; }
    public void setMaxRecipientsPerRequest(int value) { maxRecipientsPerRequest = value; }
    public int getMaxChannelsPerRequest() { return maxChannelsPerRequest; }
    public void setMaxChannelsPerRequest(int value) { maxChannelsPerRequest = value; }
    public Duration getKafkaPublishTimeout() { return kafkaPublishTimeout; }
    public void setKafkaPublishTimeout(Duration value) { kafkaPublishTimeout = value; }
    public Duration getAcceptanceTtl() { return acceptanceTtl; }
    public void setAcceptanceTtl(Duration value) { acceptanceTtl = value; }
}

@ConfigurationProperties("notification.broker")
class NotificationBrokerProperties {
    private String intake = "kafka";
    private String delivery = "rabbitmq";

    public String getIntake() { return intake; }
    public void setIntake(String value) { intake = value; }
    public String getDelivery() { return delivery; }
    public void setDelivery(String value) { delivery = value; }

    boolean kafkaIntake() {
        return "kafka".equalsIgnoreCase(intake);
    }
}

@ConfigurationProperties("notification.kafka.topics")
class NotificationKafkaTopics {
    private String requests = "notification.requests.v1";

    public String getRequests() { return requests; }
    public void setRequests(String value) { requests = value; }
}

@Service
class NotificationIntakeRouter {
    private final NotificationApiServiceApplication.NotificationSubmissionService legacyService;
    private final KafkaNotificationIntakeService kafkaService;
    private final NotificationBrokerProperties brokerProperties;
    private final RedisAdmissionControl admissionControl;
    private final MeterRegistry meterRegistry;

    NotificationIntakeRouter(
            NotificationApiServiceApplication.NotificationSubmissionService legacyService,
            KafkaNotificationIntakeService kafkaService,
            NotificationBrokerProperties brokerProperties,
            RedisAdmissionControl admissionControl,
            MeterRegistry meterRegistry) {
        this.legacyService = legacyService;
        this.kafkaService = kafkaService;
        this.brokerProperties = brokerProperties;
        this.admissionControl = admissionControl;
        this.meterRegistry = meterRegistry;
    }

    NotificationApiServiceApplication.NotificationAccepted submit(
            NotificationApiServiceApplication.NotificationRequest request, String correlationId) {
        String mode = brokerProperties.kafkaIntake() ? "kafka" : "legacy";
        String tenantId = tenantId(request);
        meterRegistry.counter("notification_intake_requests_total", "mode", mode).increment();
        try {
            NotificationApiServiceApplication.NotificationAccepted accepted = admissionControl.execute(
                    tenantId,
                    () -> brokerProperties.kafkaIntake()
                            ? kafkaService.submit(request, correlationId, tenantId)
                            : legacyService.submit(request, correlationId));
            meterRegistry.counter("notification_intake_accepted_total", "mode", mode).increment();
            meterRegistry.counter(
                    "notification_intake_accepted_by_tenant_total",
                    "tenant_bucket", tenantBucket(tenantId)).increment();
            return accepted;
        } catch (RuntimeException exception) {
            meterRegistry.counter("notification_intake_rejected_total", "reason", rejectionReason(exception)).increment();
            throw exception;
        }
    }

    static String tenantId(NotificationApiServiceApplication.NotificationRequest request) {
        return request.tenantId() == null || request.tenantId().isBlank()
                ? request.productId()
                : request.tenantId();
    }

    static String partitionKey(String tenantId, String recipientId) {
        return tenantId + ":" + recipientId;
    }

    private static String tenantBucket(String tenantId) {
        return String.format("%02d", Math.floorMod(tenantId.hashCode(), 32));
    }

    private static String rejectionReason(RuntimeException exception) {
        if (exception instanceof RateLimitExceededException) return "rate_limited";
        if (exception instanceof IntakeCapacityExceededException) return "concurrency";
        if (exception instanceof KafkaPublishException) return "kafka_unavailable";
        if (exception instanceof AdmissionControlUnavailableException) return "admission_unavailable";
        return "request_failed";
    }
}

@Service
class KafkaNotificationIntakeService {
    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final NotificationKafkaTopics topics;
    private final NotificationIntakeProperties properties;
    private final AcceptanceCache acceptanceCache;
    private final MeterRegistry meterRegistry;
    private final Timer publishLatency;

    KafkaNotificationIntakeService(
            KafkaTemplate<Object, Object> kafkaTemplate,
            NotificationKafkaTopics topics,
            NotificationIntakeProperties properties,
            AcceptanceCache acceptanceCache,
            MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
        this.properties = properties;
        this.acceptanceCache = acceptanceCache;
        this.meterRegistry = meterRegistry;
        this.publishLatency = Timer.builder("notification_intake_kafka_publish_latency")
                .description("Time to receive a durable Kafka acknowledgement")
                .register(meterRegistry);
    }

    NotificationApiServiceApplication.NotificationAccepted submit(
            NotificationApiServiceApplication.NotificationRequest request,
            String correlationId,
            String tenantId) {
        validateFanOutLimits();
        Optional<NotificationApiServiceApplication.NotificationAccepted> duplicate =
                acceptanceCache.findByIdempotency(tenantId, request.idempotencyKey());
        if (duplicate.isPresent()) {
            meterRegistry.counter("notification_intake_idempotency_hits_total").increment();
            return duplicate.get();
        }

        UUID notificationId = request.notificationId() == null ? UUID.randomUUID() : request.notificationId();
        UUID requestId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant acceptedAt = Instant.now();
        String channel = request.channel().toUpperCase();
        NotificationRequested event = new NotificationRequested(
                eventId.toString(),
                notificationId.toString(),
                requestId.toString(),
                tenantId,
                request.idempotencyKey(),
                request.templateKey(),
                recipient(request, channel),
                List.of(channel),
                request.variables(),
                acceptedAt,
                1);
        String partitionKey = NotificationIntakeRouter.partitionKey(tenantId, request.userId());

        Timer.Sample sample = Timer.start(meterRegistry);
        long deadlineNanos = System.nanoTime() + properties.getKafkaPublishTimeout().toNanos();
        try {
            var sendFuture = kafkaTemplate.send(topics.getRequests(), partitionKey, event);
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new TimeoutException("Kafka send exhausted the intake publication deadline");
            }
            sendFuture.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            recordPublishFailure("interrupted", exception);
        } catch (TimeoutException exception) {
            meterRegistry.counter("notification_intake_kafka_producer_buffer_exhaustion_total").increment();
            recordPublishFailure("timeout", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof BufferExhaustedException || cause instanceof org.apache.kafka.common.errors.TimeoutException) {
                meterRegistry.counter("notification_intake_kafka_producer_buffer_exhaustion_total").increment();
            }
            recordPublishFailure("broker_error", cause);
        } catch (KafkaException exception) {
            recordPublishFailure("producer_error", exception);
        } finally {
            sample.stop(publishLatency);
        }

        NotificationApiServiceApplication.NotificationAccepted accepted =
                new NotificationApiServiceApplication.NotificationAccepted(
                        notificationId, requestId, "ACCEPTED", acceptedAt, correlationId, channel, null);
        try {
            acceptanceCache.store(tenantId, request.idempotencyKey(), accepted);
        } catch (AdmissionControlUnavailableException exception) {
            // Kafka is already the durable source of truth. A cache outage must not turn an accepted command into an error.
            meterRegistry.counter("notification_intake_acceptance_cache_failures_total").increment();
        }
        return accepted;
    }

    private void validateFanOutLimits() {
        // The current public contract is one recipient and one channel. These checks keep limits explicit while
        // preserving that contract; multi-recipient fan-out belongs in the orchestrator migration.
        if (properties.getMaxRecipientsPerRequest() < 1 || properties.getMaxChannelsPerRequest() < 1) {
            throw new IntakeCapacityExceededException("The configured recipient or channel limit rejects this request");
        }
    }

    private NotificationRequested.Recipient recipient(
            NotificationApiServiceApplication.NotificationRequest request, String channel) {
        return new NotificationRequested.Recipient(
                request.userId(),
                "EMAIL".equals(channel) ? request.destination() : null,
                "SMS".equals(channel) ? request.destination() : null,
                "PUSH".equals(channel) ? request.destination() : null,
                "WEBHOOK".equals(channel) ? request.destination() : null);
    }

    private void recordPublishFailure(String reason, Throwable cause) {
        meterRegistry.counter("notification_intake_kafka_publish_failures_total", "reason", reason).increment();
        throw new KafkaPublishException("Kafka did not durably accept the notification request", cause);
    }
}

@Service
class RedisAdmissionControl {
    private static final DefaultRedisScript<Long> RATE_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return current
            """, Long.class);

    private final StringRedisTemplate redis;
    private final NotificationIntakeProperties properties;
    private final MeterRegistry meterRegistry;
    private final Semaphore concurrentRequests;
    private final AtomicInteger activeRequests = new AtomicInteger();

    RedisAdmissionControl(
            StringRedisTemplate redis,
            NotificationIntakeProperties properties,
            MeterRegistry meterRegistry) {
        this.redis = redis;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.concurrentRequests = new Semaphore(properties.getMaxConcurrentRequests());
        Gauge.builder("notification_intake_concurrent_requests", activeRequests, AtomicInteger::get)
                .register(meterRegistry);
    }

    <T> T execute(String tenantId, Supplier<T> work) {
        checkRate("notification:intake:rate:global:" + Instant.now().getEpochSecond(),
                properties.getGlobalRatePerSecond(), "global");
        checkRate("notification:intake:rate:tenant:" + keyHash(tenantId) + ":" + Instant.now().getEpochSecond(),
                properties.getPerTenantRatePerSecond(), "tenant");
        if (!concurrentRequests.tryAcquire()) {
            meterRegistry.counter("notification_intake_rate_limited_total", "scope", "concurrency").increment();
            throw new IntakeCapacityExceededException("Notification intake is at its concurrent request limit");
        }
        activeRequests.incrementAndGet();
        try {
            return work.get();
        } finally {
            activeRequests.decrementAndGet();
            concurrentRequests.release();
        }
    }

    private void checkRate(String key, long limit, String scope) {
        try {
            Long count = redis.execute(RATE_SCRIPT, List.of(key), "2");
            if (count != null && count > limit) {
                meterRegistry.counter("notification_intake_rate_limited_total", "scope", scope).increment();
                throw new RateLimitExceededException(scope);
            }
        } catch (RateLimitExceededException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new AdmissionControlUnavailableException("Redis admission control is unavailable", exception);
        }
    }

    private static String keyHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

@Service
class AcceptanceCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final NotificationIntakeProperties properties;

    AcceptanceCache(StringRedisTemplate redis, ObjectMapper objectMapper, NotificationIntakeProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    void store(
            String tenantId,
            String idempotencyKey,
            NotificationApiServiceApplication.NotificationAccepted accepted) {
        try {
            String json = objectMapper.writeValueAsString(accepted);
            redis.opsForValue().set(acceptanceKey(accepted.notificationId()), json, properties.getAcceptanceTtl());
            redis.opsForValue().set(idempotencyKey(tenantId, idempotencyKey), json, properties.getAcceptanceTtl());
        } catch (JsonProcessingException | DataAccessException exception) {
            throw new AdmissionControlUnavailableException("Acceptance cache write failed", exception);
        }
    }

    Optional<NotificationApiServiceApplication.NotificationAccepted> find(UUID notificationId) {
        return read(acceptanceKey(notificationId));
    }

    Optional<NotificationApiServiceApplication.NotificationAccepted> findByIdempotency(
            String tenantId, String idempotencyKey) {
        return read(idempotencyKey(tenantId, idempotencyKey));
    }

    private Optional<NotificationApiServiceApplication.NotificationAccepted> read(String key) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null
                    ? Optional.empty()
                    : Optional.of(objectMapper.readValue(
                            json, NotificationApiServiceApplication.NotificationAccepted.class));
        } catch (JsonProcessingException | DataAccessException exception) {
            throw new AdmissionControlUnavailableException("Acceptance cache read failed", exception);
        }
    }

    private static String acceptanceKey(UUID notificationId) {
        return "notification:acceptance:" + notificationId;
    }

    private static String idempotencyKey(String tenantId, String idempotencyKey) {
        return "notification:intake:idempotency:" + RedisAdmissionControlKey.hash(tenantId + ":" + idempotencyKey);
    }
}

final class RedisAdmissionControlKey {
    private RedisAdmissionControlKey() {
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

@Service
class NotificationStatusLookupService {
    private final NotificationApiServiceApplication.NotificationRepository repository;
    private final AcceptanceCache acceptanceCache;

    NotificationStatusLookupService(
            NotificationApiServiceApplication.NotificationRepository repository,
            AcceptanceCache acceptanceCache) {
        this.repository = repository;
        this.acceptanceCache = acceptanceCache;
    }

    NotificationApiServiceApplication.NotificationStatus find(UUID notificationId) {
        Optional<NotificationApiServiceApplication.NotificationRecord> projection = repository.findOptionalById(notificationId);
        if (projection.isPresent()) {
            NotificationApiServiceApplication.NotificationRecord record = projection.get();
            return new NotificationApiServiceApplication.NotificationStatus(
                    record.id(), record.status(), record.channel(), record.updatedAt());
        }
        return acceptanceCache.find(notificationId)
                .map(accepted -> new NotificationApiServiceApplication.NotificationStatus(
                        accepted.notificationId(), accepted.status(), accepted.channel(), accepted.acceptedAt()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Notification not found: " + notificationId));
    }
}

@Component("kafkaHealthIndicator")
class KafkaIntakeHealthIndicator implements HealthIndicator, AutoCloseable {
    private final AdminClient adminClient;

    KafkaIntakeHealthIndicator(KafkaAdmin kafkaAdmin) {
        this.adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties());
    }

    @Override
    public Health health() {
        try {
            String clusterId = adminClient.describeCluster().clusterId().get(2, TimeUnit.SECONDS);
            return Health.up().withDetail("clusterId", clusterId).build();
        } catch (Exception exception) {
            return Health.down().withException(exception).build();
        }
    }

    @Override
    public void close() {
        adminClient.close(Duration.ofSeconds(1));
    }
}

@Component
class RequestSizeLimitFilter extends OncePerRequestFilter {
    private final NotificationIntakeProperties properties;
    private final DistributionSummary payloadSize;

    RequestSizeLimitFilter(NotificationIntakeProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.payloadSize = DistributionSummary.builder("notification_intake_request_payload_bytes")
                .baseUnit("bytes")
                .register(meterRegistry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod())
                && "/notifications".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, jakarta.servlet.FilterChain filterChain)
            throws ServletException, IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > properties.getMaxRequestBytes()) {
            reject(response);
            return;
        }
        LimitedRequestWrapper wrapped = new LimitedRequestWrapper(request, properties.getMaxRequestBytes());
        try {
            filterChain.doFilter(wrapped, response);
            payloadSize.record(wrapped.bytesRead());
        } catch (RuntimeException exception) {
            if (hasPayloadTooLargeCause(exception)) {
                reject(response);
                return;
            }
            throw exception;
        }
    }

    private boolean hasPayloadTooLargeCause(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof PayloadTooLargeIOException) return true;
            current = current.getCause();
        }
        return false;
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"title\":\"Request body too large\",\"status\":413}");
    }
}

class LimitedRequestWrapper extends HttpServletRequestWrapper {
    private final long limit;
    private LimitedServletInputStream inputStream;

    LimitedRequestWrapper(HttpServletRequest request, long limit) {
        super(request);
        this.limit = limit;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (inputStream == null) inputStream = new LimitedServletInputStream(super.getInputStream(), limit);
        return inputStream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(getInputStream(),
                getCharacterEncoding() == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(getCharacterEncoding())));
    }

    long bytesRead() {
        return inputStream == null ? 0 : inputStream.bytesRead();
    }
}

class LimitedServletInputStream extends ServletInputStream {
    private final ServletInputStream delegate;
    private final long limit;
    private long bytesRead;

    LimitedServletInputStream(ServletInputStream delegate, long limit) {
        this.delegate = delegate;
        this.limit = limit;
    }

    @Override public boolean isFinished() { return delegate.isFinished(); }
    @Override public boolean isReady() { return delegate.isReady(); }
    @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }

    @Override
    public int read() throws IOException {
        int value = delegate.read();
        if (value >= 0) add(1);
        return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        int count = delegate.read(bytes, offset, length);
        if (count > 0) add(count);
        return count;
    }

    long bytesRead() { return bytesRead; }

    private void add(int count) throws PayloadTooLargeIOException {
        bytesRead += count;
        if (bytesRead > limit) throw new PayloadTooLargeIOException();
    }
}

class PayloadTooLargeIOException extends IOException {
}

@RestControllerAdvice
class IntakeExceptionHandler {
    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ProblemDetail> rateLimited(RateLimitExceededException exception) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage(), true);
    }

    @ExceptionHandler(IntakeCapacityExceededException.class)
    ResponseEntity<ProblemDetail> capacity(IntakeCapacityExceededException exception) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage(), true);
    }

    @ExceptionHandler({KafkaPublishException.class, AdmissionControlUnavailableException.class})
    ResponseEntity<ProblemDetail> unavailable(RuntimeException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), true);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail, boolean retryable) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, retryable ? "1" : "")
                .body(problem);
    }
}

class RateLimitExceededException extends RuntimeException {
    RateLimitExceededException(String scope) {
        super("Notification intake " + scope + " rate limit exceeded");
    }
}

class IntakeCapacityExceededException extends RuntimeException {
    IntakeCapacityExceededException(String message) { super(message); }
}

class KafkaPublishException extends RuntimeException {
    KafkaPublishException(String message, Throwable cause) { super(message, cause); }
}

class AdmissionControlUnavailableException extends RuntimeException {
    AdmissionControlUnavailableException(String message, Throwable cause) { super(message, cause); }
}
