package com.notificationplatform.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@SpringBootApplication
public class TemplateServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TemplateServiceApplication.class, args);
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
    @RequestMapping("/templates")
    static class TemplateController {
        private final TemplateRepository repository;

        TemplateController(TemplateRepository repository) {
            this.repository = repository;
        }

        @GetMapping
        List<Template> list() {
            return repository.findAll();
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        Template create(@Valid @RequestBody TemplateRequest request) {
            Template template = new Template(
                    UUID.randomUUID(),
                    request.productId(),
                    request.key(),
                    request.channel().toUpperCase(),
                    request.subject(),
                    request.body(),
                    request.requiredVariables() == null ? List.of() : request.requiredVariables(),
                    "ACTIVE",
                    Instant.now(),
                    Instant.now());
            return repository.save(template);
        }

        @PutMapping("/{id}")
        Template update(@PathVariable UUID id, @Valid @RequestBody TemplateRequest request) {
            Template existing = repository.findById(id);
            Template updated = new Template(
                    existing.id(),
                    request.productId(),
                    request.key(),
                    request.channel().toUpperCase(),
                    request.subject(),
                    request.body(),
                    request.requiredVariables() == null ? List.of() : request.requiredVariables(),
                    "ACTIVE",
                    existing.createdAt(),
                    Instant.now());
            return repository.update(updated);
        }

        @PostMapping("/{id}/preview")
        RenderedTemplate preview(@PathVariable UUID id, @RequestBody RenderRequest request) {
            return render(repository.findById(id), request.variables());
        }

        @PostMapping("/render")
        RenderedTemplate render(@Valid @RequestBody RenderTemplateRequest request) {
            Template template = repository.findActive(request.productId(), request.templateKey(), request.channel().toUpperCase());
            return render(template, request.variables());
        }

        private RenderedTemplate render(Template template, Map<String, Object> variables) {
            Map<String, Object> safeVariables = variables == null ? Map.of() : variables;
            List<String> missing = template.requiredVariables().stream()
                    .filter(required -> !safeVariables.containsKey(required))
                    .toList();
            if (!missing.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing template variables: " + missing);
            }
            return new RenderedTemplate(
                    template.id(),
                    renderText(template.subject(), safeVariables),
                    renderText(template.body(), safeVariables),
                    missing);
        }

        private String renderText(String source, Map<String, Object> variables) {
            String rendered = source == null ? "" : source;
            for (Map.Entry<String, Object> entry : new LinkedHashMap<>(variables).entrySet()) {
                rendered = rendered.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
            }
            return rendered;
        }
    }

    @Repository
    static class TemplateRepository {
        private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
        };

        private final JdbcTemplate jdbc;
        private final ObjectMapper objectMapper;

        TemplateRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
            this.jdbc = jdbc;
            this.objectMapper = objectMapper;
        }

        List<Template> findAll() {
            return jdbc.query("""
                    SELECT id, product_id, template_key, channel, subject, body, required_variables, status, created_at, updated_at
                    FROM notification_templates
                    ORDER BY updated_at DESC
                    """, this::map);
        }

        Template findById(UUID id) {
            try {
                return jdbc.queryForObject("""
                        SELECT id, product_id, template_key, channel, subject, body, required_variables, status, created_at, updated_at
                        FROM notification_templates
                        WHERE id = ?
                        """, this::map, id);
            } catch (EmptyResultDataAccessException exception) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found: " + id);
            }
        }

        Template findActive(String productId, String key, String channel) {
            try {
                return jdbc.queryForObject("""
                        SELECT id, product_id, template_key, channel, subject, body, required_variables, status, created_at, updated_at
                        FROM notification_templates
                        WHERE product_id = ? AND template_key = ? AND channel = ? AND status = 'ACTIVE'
                        ORDER BY updated_at DESC
                        LIMIT 1
                        """, this::map, productId, key, channel);
            } catch (EmptyResultDataAccessException exception) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active template not found: " + key);
            }
        }

        @Transactional
        Template save(Template template) {
            jdbc.update("""
                    INSERT INTO notification_templates (
                        id, product_id, template_key, channel, subject, body, required_variables, status, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                    """,
                    template.id(), template.productId(), template.key(), template.channel(), template.subject(),
                    template.body(), writeJson(template.requiredVariables()), template.status(), ts(template.createdAt()), ts(template.updatedAt()));
            return template;
        }

        @Transactional
        Template update(Template template) {
            jdbc.update("""
                    UPDATE notification_templates
                    SET product_id = ?, template_key = ?, channel = ?, subject = ?, body = ?, required_variables = ?::jsonb,
                        status = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    template.productId(), template.key(), template.channel(), template.subject(), template.body(),
                    writeJson(template.requiredVariables()), template.status(), ts(template.updatedAt()), template.id());
            return template;
        }

        private Template map(ResultSet rs, int rowNum) throws SQLException {
            return new Template(
                    rs.getObject("id", UUID.class),
                    rs.getString("product_id"),
                    rs.getString("template_key"),
                    rs.getString("channel"),
                    rs.getString("subject"),
                    rs.getString("body"),
                    readVariables(rs.getString("required_variables")),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        }

        private List<String> readVariables(String json) {
            try {
                return json == null ? List.of() : objectMapper.readValue(json, STRING_LIST);
            } catch (Exception exception) {
                throw new IllegalStateException("Could not read template variables", exception);
            }
        }

        private String writeJson(Object value) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (Exception exception) {
                throw new IllegalArgumentException("Could not write JSON", exception);
            }
        }
    }

    record Health(String status, Instant checkedAt) {
    }

    record Template(
            UUID id,
            String productId,
            String key,
            String channel,
            String subject,
            String body,
            List<String> requiredVariables,
            String status,
            Instant createdAt,
            Instant updatedAt) {
    }

    record TemplateRequest(
            @NotBlank String productId,
            @NotBlank String key,
            @NotBlank String channel,
            @NotBlank String subject,
            @NotBlank String body,
            List<String> requiredVariables) {
    }

    record RenderTemplateRequest(
            @NotBlank String productId,
            @NotBlank String templateKey,
            @NotBlank String channel,
            Map<String, Object> variables) {
    }

    record RenderRequest(Map<String, Object> variables) {
    }

    record RenderedTemplate(UUID templateId, String subject, String body, List<String> missingVariables) {
    }
}
