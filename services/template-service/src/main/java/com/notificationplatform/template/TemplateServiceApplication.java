package com.notificationplatform.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@EnableScheduling
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
    @RequestMapping("/products")
    static class ProductController {
        private final ProductRepository repository;

        ProductController(ProductRepository repository) {
            this.repository = repository;
        }

        @GetMapping
        List<Product> list() {
            return repository.findAll();
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        Product create(@Valid @RequestBody ProductRequest request) {
            Instant now = Instant.now();
            String id = normalizeProductId(request.id(), request.name());
            Product product = new Product(id, request.name().trim(), normalizeProductStatus(request.status()), now, now);
            return repository.create(product);
        }

        @PutMapping("/{id}")
        Product update(@PathVariable String id, @Valid @RequestBody ProductRequest request) {
            Product existing = repository.findById(id);
            Product updated = new Product(
                    existing.id(),
                    request.name().trim(),
                    normalizeProductStatus(request.status()),
                    existing.createdAt(),
                    Instant.now());
            return repository.update(updated);
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void delete(@PathVariable UUID id) {
            repository.delete(id);
        }
    }

    @RestController
    @RequestMapping("/templates")
    static class TemplateController {
        private final TemplateRepository repository;
        private final MeterRegistry meterRegistry;

        TemplateController(TemplateRepository repository, MeterRegistry meterRegistry) {
            this.repository = repository;
            this.meterRegistry = meterRegistry;
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
                    normalizeStatus(request.status()),
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
                    normalizeStatus(request.status()),
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
            Timer.Sample sample = Timer.start(meterRegistry);
            String status = "success";
            try {
                Map<String, Object> safeVariables = variables == null ? Map.of() : variables;
                List<String> missing = template.requiredVariables().stream()
                        .filter(required -> !safeVariables.containsKey(required))
                        .toList();
                if (!missing.isEmpty()) {
                    status = "validation_failed";
                    meterRegistry.counter("template_validation_failed_total").increment();
                    meterRegistry.counter("template_render_total", "status", status).increment();
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing template variables: " + missing);
                }
                meterRegistry.counter("template_render_total", "status", status).increment();
                return new RenderedTemplate(
                        template.id(),
                        renderText(template.subject(), safeVariables),
                        renderText(template.body(), safeVariables),
                        missing);
            } catch (RuntimeException exception) {
                if (!"validation_failed".equals(status)) {
                    status = "error";
                    meterRegistry.counter("template_render_total", "status", status).increment();
                }
                throw exception;
            } finally {
                sample.stop(Timer.builder("template_render_duration_seconds")
                        .tag("status", status)
                        .register(meterRegistry));
            }
        }

        private String renderText(String source, Map<String, Object> variables) {
            String rendered = source == null ? "" : source;
            for (Map.Entry<String, Object> entry : new LinkedHashMap<>(variables).entrySet()) {
                rendered = rendered.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
            }
            return rendered;
        }

        private String normalizeStatus(String status) {
            if (status == null || status.isBlank()) {
                return "ACTIVE";
            }
            String normalized = status.toUpperCase();
            if (!List.of("DRAFT", "ACTIVE", "ARCHIVED").contains(normalized)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported template status: " + status);
            }
            return normalized;
        }
    }

    @Repository
    static class ProductRepository {
        private final JdbcTemplate jdbc;

        ProductRepository(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        List<Product> findAll() {
            return jdbc.query("""
                    SELECT id, name, status, created_at, updated_at
                    FROM notification_products
                    ORDER BY name ASC
                    """, this::map);
        }

        Product findById(String id) {
            try {
                return jdbc.queryForObject("""
                        SELECT id, name, status, created_at, updated_at
                        FROM notification_products
                        WHERE id = ?
                        """, this::map, id);
            } catch (EmptyResultDataAccessException exception) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + id);
            }
        }

        @Transactional
        Product create(Product product) {
            try {
                jdbc.update("""
                        INSERT INTO notification_products (id, name, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        """, product.id(), product.name(), product.status(), ts(product.createdAt()), ts(product.updatedAt()));
                return product;
            } catch (org.springframework.dao.DuplicateKeyException exception) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Product already exists: " + product.id());
            }
        }

        @Transactional
        Product update(Product product) {
            jdbc.update("""
                    UPDATE notification_products
                    SET name = ?, status = ?, updated_at = ?
                    WHERE id = ?
                    """, product.name(), product.status(), ts(product.updatedAt()), product.id());
            return product;
        }

        @Transactional
        void ensureExists(String productId) {
            Instant now = Instant.now();
            jdbc.update("""
                    INSERT INTO notification_products (id, name, status, created_at, updated_at)
                    VALUES (?, ?, 'ACTIVE', ?, ?)
                    ON CONFLICT (id) DO NOTHING
                    """, productId, productId, ts(now), ts(now));
        }

        private Product map(ResultSet rs, int rowNum) throws SQLException {
            return new Product(
                    rs.getString("id"),
                    rs.getString("name"),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        }
    }

    @Repository
    static class TemplateRepository {
        private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
        };

        private final JdbcTemplate jdbc;
        private final ObjectMapper objectMapper;
        private final ProductRepository productRepository;
        private final TemplateEventOutbox eventOutbox;

        TemplateRepository(JdbcTemplate jdbc, ObjectMapper objectMapper, ProductRepository productRepository,
                TemplateEventOutbox eventOutbox) {
            this.jdbc = jdbc;
            this.objectMapper = objectMapper;
            this.productRepository = productRepository;
            this.eventOutbox = eventOutbox;
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
            productRepository.ensureExists(template.productId());
            jdbc.update("""
                    INSERT INTO notification_templates (
                        id, product_id, template_key, channel, subject, body, required_variables, status, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                    """,
                    template.id(), template.productId(), template.key(), template.channel(), template.subject(),
                    template.body(), writeJson(template.requiredVariables()), template.status(), ts(template.createdAt()), ts(template.updatedAt()));
            eventOutbox.append("TemplateCreated", template.id(), 1, template);
            return template;
        }

        @Transactional
        Template update(Template template) {
            productRepository.ensureExists(template.productId());
            jdbc.update("""
                    UPDATE notification_templates
                    SET product_id = ?, template_key = ?, channel = ?, subject = ?, body = ?, required_variables = ?::jsonb,
                        status = ?, updated_at = ?, aggregate_version = aggregate_version + 1
                    WHERE id = ?
                    """,
                    template.productId(), template.key(), template.channel(), template.subject(), template.body(),
                    writeJson(template.requiredVariables()), template.status(), ts(template.updatedAt()), template.id());
            Long version = jdbc.queryForObject("SELECT aggregate_version FROM notification_templates WHERE id=?", Long.class, template.id());
            eventOutbox.append("TemplateUpdated", template.id(), version == null ? 1 : version, template);
            return template;
        }

        @Transactional
        void delete(UUID id) {
            Template template = findById(id);
            Long version = jdbc.queryForObject("SELECT aggregate_version + 1 FROM notification_templates WHERE id=?", Long.class, id);
            eventOutbox.appendDeleted(id, version == null ? 2 : version, template);
            jdbc.update("DELETE FROM notification_templates WHERE id=?", id);
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

    private static String normalizeProductId(String requestedId, String name) {
        String source = requestedId == null || requestedId.isBlank() ? name : requestedId;
        String normalized = source.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product id could not be derived from name");
        }
        if (normalized.length() > 160) {
            return normalized.substring(0, 160).replaceAll("-$", "");
        }
        return normalized;
    }

    private static String normalizeProductStatus(String status) {
        if (status == null || status.isBlank()) {
            return "ACTIVE";
        }
        String normalized = status.toUpperCase();
        if (!List.of("ACTIVE", "DISABLED").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported product status: " + status);
        }
        return normalized;
    }

    record Health(String status, Instant checkedAt) {
    }

    record Product(
            String id,
            String name,
            String status,
            Instant createdAt,
            Instant updatedAt) {
    }

    record ProductRequest(
            String id,
            @NotBlank String name,
            String status) {
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
            String status,
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
