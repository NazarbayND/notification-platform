package com.notificationplatform.application.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.notificationplatform.application.cache.NotificationCacheService;
import com.notificationplatform.application.common.ConflictException;
import com.notificationplatform.application.common.ResourceNotFoundException;
import com.notificationplatform.domain.entity.NotificationTemplate;
import com.notificationplatform.domain.entity.Product;
import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.TemplateStatus;
import com.notificationplatform.domain.repository.NotificationTemplateRepository;
import com.notificationplatform.domain.repository.ProductRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TemplateManagementServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private NotificationTemplateRepository templateRepository;

    @Mock
    private NotificationCacheService cacheService;

    @InjectMocks
    private TemplateManagementService service;

    @Test
    void createTemplateSavesDraftTemplateByDefault() {
        UUID productId = UUID.randomUUID();
        Product product = new Product("Billing");

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(templateRepository.save(any(NotificationTemplate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        NotificationTemplate template = service.createTemplate(new CreateTemplateCommand(
            productId,
            " invoice.created ",
            Channel.EMAIL,
            1,
            " Invoice created ",
            " Hello {{name}} ",
            null
        ));

        ArgumentCaptor<NotificationTemplate> templateCaptor = ArgumentCaptor.forClass(NotificationTemplate.class);
        verify(templateRepository).save(templateCaptor.capture());

        assertThat(template).isSameAs(templateCaptor.getValue());
        assertThat(templateCaptor.getValue().getProduct()).isSameAs(product);
        assertThat(templateCaptor.getValue().getTemplateKey()).isEqualTo("invoice.created");
        assertThat(templateCaptor.getValue().getSubject()).isEqualTo("Invoice created");
        assertThat(templateCaptor.getValue().getContent()).isEqualTo("Hello {{name}}");
        assertThat(templateCaptor.getValue().getStatus()).isEqualTo(TemplateStatus.DRAFT);
    }

    @Test
    void createTemplateRejectsMissingProduct() {
        UUID productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createTemplate(new CreateTemplateCommand(
            productId,
            "invoice.created",
            Channel.EMAIL,
            1,
            null,
            "Hello",
            TemplateStatus.DRAFT
        )))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Product not found");

        verifyNoInteractions(templateRepository);
    }

    @Test
    void createTemplateRejectsDuplicateVersion() {
        UUID productId = UUID.randomUUID();
        Product product = new Product("Billing");

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(templateRepository.existsByProduct_IdAndTemplateKeyAndChannelAndVersion(
            productId,
            "invoice.created",
            Channel.EMAIL,
            1
        )).thenReturn(true);

        assertThatThrownBy(() -> service.createTemplate(new CreateTemplateCommand(
            productId,
            "invoice.created",
            Channel.EMAIL,
            1,
            null,
            "Hello",
            TemplateStatus.DRAFT
        )))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("Template version already exists");

        verify(templateRepository).existsByProduct_IdAndTemplateKeyAndChannelAndVersion(
            productId,
            "invoice.created",
            Channel.EMAIL,
            1
        );
        verifyNoMoreInteractions(templateRepository);
    }

    @Test
    void createTemplateRejectsSecondActiveTemplateForSameProductKeyAndChannel() {
        UUID productId = UUID.randomUUID();
        Product product = new Product("Billing");

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(templateRepository.existsByProduct_IdAndTemplateKeyAndChannelAndStatus(
            productId,
            "invoice.created",
            Channel.EMAIL,
            TemplateStatus.ACTIVE
        )).thenReturn(true);

        assertThatThrownBy(() -> service.createTemplate(new CreateTemplateCommand(
            productId,
            "invoice.created",
            Channel.EMAIL,
            2,
            null,
            "Hello",
            TemplateStatus.ACTIVE
        )))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("Active template already exists");
    }
}
