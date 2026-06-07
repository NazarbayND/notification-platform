package com.notificationplatform.application.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.notificationplatform.application.common.ConflictException;
import com.notificationplatform.application.common.ResourceNotFoundException;
import com.notificationplatform.domain.entity.Product;
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
class ProductManagementServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductManagementService service;

    @Test
    void createProductTrimsNameAndSavesProduct() {
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Product product = service.createProduct(new CreateProductCommand(" Billing "));

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).existsByName("Billing");
        verify(productRepository).save(productCaptor.capture());

        assertThat(product).isSameAs(productCaptor.getValue());
        assertThat(productCaptor.getValue().getName()).isEqualTo("Billing");
    }

    @Test
    void createProductRejectsDuplicateName() {
        when(productRepository.existsByName("Billing")).thenReturn(true);

        assertThatThrownBy(() -> service.createProduct(new CreateProductCommand("Billing")))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("Product already exists");

        verify(productRepository).existsByName("Billing");
        verifyNoMoreInteractions(productRepository);
    }

    @Test
    void getProductRejectsMissingProduct() {
        UUID productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProduct(productId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Product not found");
    }
}
