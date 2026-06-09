package com.notificationplatform.application.management;

import com.notificationplatform.application.common.ConflictException;
import com.notificationplatform.application.common.ResourceNotFoundException;
import com.notificationplatform.application.cache.NotificationCacheService;
import com.notificationplatform.domain.entity.NotificationTemplate;
import com.notificationplatform.domain.entity.Product;
import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.TemplateStatus;
import com.notificationplatform.domain.repository.NotificationTemplateRepository;
import com.notificationplatform.domain.repository.ProductRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TemplateManagementService {

    private final ProductRepository productRepository;
    private final NotificationTemplateRepository templateRepository;
    private final NotificationCacheService cacheService;

    public TemplateManagementService(
        ProductRepository productRepository,
        NotificationTemplateRepository templateRepository,
        NotificationCacheService cacheService
    ) {
        this.productRepository = productRepository;
        this.templateRepository = templateRepository;
        this.cacheService = cacheService;
    }

    @Transactional
    public NotificationTemplate createTemplate(CreateTemplateCommand command) {
        Objects.requireNonNull(command, "Create template command is required");
        Objects.requireNonNull(command.productId(), "Product id is required");
        Channel channel = Objects.requireNonNull(command.channel(), "Template channel is required");
        String templateKey = normalizeRequired(command.templateKey(), "Template key is required");
        String content = normalizeRequired(command.content(), "Template content is required");
        TemplateStatus status = command.status() == null ? TemplateStatus.DRAFT : command.status();

        if (command.version() < 1) {
            throw new IllegalArgumentException("Template version must be greater than zero");
        }

        Product product = productRepository.findById(command.productId())
            .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + command.productId()));

        if (templateRepository.existsByProduct_IdAndTemplateKeyAndChannelAndVersion(
            command.productId(),
            templateKey,
            channel,
            command.version()
        )) {
            throw new ConflictException("Template version already exists");
        }

        if (status == TemplateStatus.ACTIVE && templateRepository.existsByProduct_IdAndTemplateKeyAndChannelAndStatus(
            command.productId(),
            templateKey,
            channel,
            TemplateStatus.ACTIVE
        )) {
            throw new ConflictException("Active template already exists for product, key, and channel");
        }

        NotificationTemplate template = new NotificationTemplate(
            product,
            templateKey,
            channel,
            command.version(),
            content
        );
        template.setSubject(trimToNull(command.subject()));
        template.setStatus(status);

        NotificationTemplate savedTemplate = templateRepository.save(template);
        if (savedTemplate.getStatus() == TemplateStatus.ACTIVE) {
            cacheService.evictActiveTemplate(command.productId(), templateKey, channel);
        }
        return savedTemplate;
    }

    @Transactional(readOnly = true)
    public List<NotificationTemplate> listTemplates(UUID productId) {
        Objects.requireNonNull(productId, "Product id is required");

        if (!productRepository.existsById(productId)) {
            throw new ResourceNotFoundException("Product not found: " + productId);
        }

        return templateRepository.findByProduct_IdOrderByCreatedAtDesc(productId);
    }

    @Transactional(readOnly = true)
    public NotificationTemplate getActiveTemplate(UUID productId, String templateKey, Channel channel) {
        Objects.requireNonNull(productId, "Product id is required");
        Objects.requireNonNull(channel, "Template channel is required");
        String normalizedTemplateKey = normalizeRequired(templateKey, "Template key is required");

        return cacheService.getActiveTemplateId(productId, normalizedTemplateKey, channel)
            .flatMap(templateRepository::findById)
            .filter(template -> template.getStatus() == TemplateStatus.ACTIVE)
            .orElseGet(() -> {
                NotificationTemplate template = templateRepository.findByProduct_IdAndTemplateKeyAndChannelAndStatus(
            productId,
            normalizedTemplateKey,
            channel,
            TemplateStatus.ACTIVE
                ).orElseThrow(() -> new ResourceNotFoundException("Active template not found"));
                cacheService.putActiveTemplateId(productId, normalizedTemplateKey, channel, template.getId());
                return template;
            });
    }

    private static String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
