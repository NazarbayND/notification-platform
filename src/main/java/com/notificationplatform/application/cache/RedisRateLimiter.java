package com.notificationplatform.application.cache;

import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryAcquire(String key, long limit, Duration window) {
        Objects.requireNonNull(key, "Rate limit key is required");
        Objects.requireNonNull(window, "Rate limit window is required");
        if (limit < 1) {
            throw new IllegalArgumentException("Rate limit must be greater than zero");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Rate limit window must be positive");
        }

        String redisKey = "np:rate-limit:" + key;
        try {
            Long currentCount = redisTemplate.opsForValue().increment(redisKey);
            if (currentCount != null && currentCount == 1L) {
                redisTemplate.expire(redisKey, window);
            }
            return currentCount == null || currentCount <= limit;
        } catch (RuntimeException ex) {
            log.warn("Redis rate-limit check failed; allowing request: key={}", redisKey, ex);
            return true;
        }
    }
}
