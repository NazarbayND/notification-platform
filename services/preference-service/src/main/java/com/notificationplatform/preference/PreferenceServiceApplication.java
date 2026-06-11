package com.notificationplatform.preference;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@SpringBootApplication
public class PreferenceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PreferenceServiceApplication.class, args);
    }

    private static java.sql.Timestamp ts(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }

    @RestController
    static class HealthController {
        @GetMapping({"/health/live", "/health/ready"})
        Health health() {
            return new Health("UP", Instant.now());
        }
    }

    @RestController
    @RequestMapping("/preferences")
    static class PreferenceController {
        private final PreferenceRepository repository;

        PreferenceController(PreferenceRepository repository) {
            this.repository = repository;
        }

        @GetMapping
        List<Preference> list(
                @RequestParam(required = false) String productId,
                @RequestParam(required = false) String userId,
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "50") int size) {
            return repository.findAll(productId, userId, page, size);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        Preference create(@Valid @RequestBody PreferenceRequest request) {
            return repository.upsert(request);
        }

        @PutMapping("/{id}")
        Preference update(@PathVariable UUID id, @Valid @RequestBody PreferenceRequest request) {
            return repository.update(id, request);
        }

        @GetMapping("/{id}")
        Preference get(@PathVariable UUID id) {
            return repository.findById(id);
        }

        @GetMapping("/check")
        PreferenceDecision check(
                @RequestParam String userId,
                @RequestParam String productId,
                @RequestParam String channel) {
            return repository.decision(userId, productId, channel.toUpperCase());
        }
    }

    @Repository
    static class PreferenceRepository {
        private final JdbcTemplate jdbc;

        PreferenceRepository(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        List<Preference> findAll(String productId, String userId, int page, int size) {
            int limit = Math.max(1, Math.min(size, 200));
            int offset = Math.max(page, 0) * limit;
            if (productId != null && userId != null) {
                return jdbc.query("""
                        SELECT id, user_id, product_id, channel, allowed, created_at, updated_at
                        FROM user_notification_preferences
                        WHERE product_id = ? AND user_id = ?
                        ORDER BY updated_at DESC
                        LIMIT ? OFFSET ?
                        """, this::map, productId, userId, limit, offset);
            }
            if (productId != null) {
                return jdbc.query("""
                        SELECT id, user_id, product_id, channel, allowed, created_at, updated_at
                        FROM user_notification_preferences
                        WHERE product_id = ?
                        ORDER BY updated_at DESC
                        LIMIT ? OFFSET ?
                        """, this::map, productId, limit, offset);
            }
            return jdbc.query("""
                    SELECT id, user_id, product_id, channel, allowed, created_at, updated_at
                    FROM user_notification_preferences
                    ORDER BY updated_at DESC
                    LIMIT ? OFFSET ?
                    """, this::map, limit, offset);
        }

        Preference findById(UUID id) {
            try {
                return jdbc.queryForObject("""
                        SELECT id, user_id, product_id, channel, allowed, created_at, updated_at
                        FROM user_notification_preferences
                        WHERE id = ?
                        """, this::map, id);
            } catch (EmptyResultDataAccessException exception) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Preference not found: " + id);
            }
        }

        PreferenceDecision decision(String userId, String productId, String channel) {
            Boolean allowed = jdbc.query("""
                    SELECT allowed
                    FROM user_notification_preferences
                    WHERE user_id = ? AND product_id = ? AND channel = ?
                    """, rs -> rs.next() ? rs.getBoolean("allowed") : null, userId, productId, channel);
            return new PreferenceDecision(userId, productId, channel, allowed == null || allowed);
        }

        @Transactional
        Preference upsert(PreferenceRequest request) {
            UUID id = UUID.randomUUID();
            Instant now = Instant.now();
            return jdbc.queryForObject("""
                    INSERT INTO user_notification_preferences (id, user_id, product_id, channel, allowed, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (user_id, product_id, channel)
                    DO UPDATE SET allowed = EXCLUDED.allowed, updated_at = EXCLUDED.updated_at
                    RETURNING id, user_id, product_id, channel, allowed, created_at, updated_at
                    """, this::map, id, request.userId(), request.productId(), request.channel().toUpperCase(), request.allowed(), ts(now), ts(now));
        }

        @Transactional
        Preference update(UUID id, PreferenceRequest request) {
            Preference updated = jdbc.queryForObject("""
                    UPDATE user_notification_preferences
                    SET user_id = ?, product_id = ?, channel = ?, allowed = ?, updated_at = ?
                    WHERE id = ?
                    RETURNING id, user_id, product_id, channel, allowed, created_at, updated_at
                    """, this::map, request.userId(), request.productId(), request.channel().toUpperCase(), request.allowed(), ts(Instant.now()), id);
            if (updated == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Preference not found: " + id);
            }
            return updated;
        }

        private Preference map(ResultSet rs, int rowNum) throws SQLException {
            return new Preference(
                    rs.getObject("id", UUID.class),
                    rs.getString("user_id"),
                    rs.getString("product_id"),
                    rs.getString("channel"),
                    rs.getBoolean("allowed"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        }
    }

    record Health(String status, Instant checkedAt) {
    }

    record Preference(
            UUID id,
            String userId,
            String productId,
            String channel,
            boolean allowed,
            Instant createdAt,
            Instant updatedAt) {
    }

    record PreferenceRequest(
            @NotBlank String userId,
            @NotBlank String productId,
            @NotBlank String channel,
            boolean allowed) {
    }

    record PreferenceDecision(String userId, String productId, String channel, boolean allowed) {
    }
}
