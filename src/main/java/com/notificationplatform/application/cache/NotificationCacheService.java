package com.notificationplatform.application.cache;

import com.notificationplatform.application.observability.NotificationMetrics;
import com.notificationplatform.domain.model.Channel;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class NotificationCacheService {

    private static final Logger log = LoggerFactory.getLogger(NotificationCacheService.class);

    private final StringRedisTemplate redisTemplate;
    private final NotificationMetrics metrics;
    private final Duration templateTtl;
    private final Duration preferenceTtl;
    private final Duration idempotencyTtl;

    public NotificationCacheService(
        StringRedisTemplate redisTemplate,
        NotificationMetrics metrics,
        @Value("${notification.cache.template-ttl:PT10M}") Duration templateTtl,
        @Value("${notification.cache.preference-ttl:PT10M}") Duration preferenceTtl,
        @Value("${notification.cache.idempotency-ttl:PT30M}") Duration idempotencyTtl
    ) {
        this.redisTemplate = redisTemplate;
        this.metrics = metrics;
        this.templateTtl = templateTtl;
        this.preferenceTtl = preferenceTtl;
        this.idempotencyTtl = idempotencyTtl;
    }

    public Optional<UUID> getActiveTemplateId(UUID productId, String templateKey, Channel channel) {
        return getUuid(templateKey(productId, templateKey, channel));
    }

    public void putActiveTemplateId(UUID productId, String templateKey, Channel channel, UUID templateId) {
        put(templateKey(productId, templateKey, channel), templateId.toString(), templateTtl);
    }

    public void evictActiveTemplate(UUID productId, String templateKey, Channel channel) {
        evict(templateKey(productId, templateKey, channel));
    }

    public Optional<Boolean> getPreferenceEnabled(UUID productId, String externalUserId, String category, Channel channel) {
        return get(preferenceKey(productId, externalUserId, category, channel)).map(Boolean::parseBoolean);
    }

    public void putPreferenceEnabled(UUID productId, String externalUserId, String category, Channel channel, boolean enabled) {
        put(preferenceKey(productId, externalUserId, category, channel), Boolean.toString(enabled), preferenceTtl);
    }

    public void evictPreference(UUID productId, String externalUserId, String category, Channel channel) {
        evict(preferenceKey(productId, externalUserId, category, channel));
    }

    public Optional<UUID> getIdempotentNotificationId(UUID productId, String idempotencyKey) {
        return getUuid(idempotencyKey(productId, idempotencyKey));
    }

    public void putIdempotentNotificationId(UUID productId, String idempotencyKey, UUID notificationRequestId) {
        put(idempotencyKey(productId, idempotencyKey), notificationRequestId.toString(), idempotencyTtl);
    }

    public void evictIdempotentNotificationId(UUID productId, String idempotencyKey) {
        evict(idempotencyKey(productId, idempotencyKey));
    }

    private Optional<UUID> getUuid(String key) {
        return get(key).map(UUID::fromString);
    }

    private Optional<String> get(String key) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                metrics.incrementRedisCacheMiss();
                return Optional.empty();
            }
            metrics.incrementRedisCacheHit();
            return Optional.of(value);
        } catch (RuntimeException ex) {
            metrics.incrementRedisCacheMiss();
            log.warn("Redis cache read failed; falling back to PostgreSQL: key={}", key, ex);
            return Optional.empty();
        }
    }

    private void put(String key, String value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (RuntimeException ex) {
            log.warn("Redis cache write failed; PostgreSQL remains source of truth: key={}", key, ex);
        }
    }

    private void evict(String key) {
        try {
            redisTemplate.delete(key);
            metrics.incrementRedisCacheEviction();
        } catch (RuntimeException ex) {
            log.warn("Redis cache eviction failed: key={}", key, ex);
        }
    }

    private static String templateKey(UUID productId, String templateKey, Channel channel) {
        return "np:template:active:" + productId + ":" + templateKey + ":" + channel;
    }

    private static String preferenceKey(UUID productId, String externalUserId, String category, Channel channel) {
        return "np:preference:" + productId + ":" + externalUserId + ":" + category + ":" + channel;
    }

    private static String idempotencyKey(UUID productId, String idempotencyKey) {
        return "np:idempotency:" + productId + ":" + idempotencyKey;
    }
}
