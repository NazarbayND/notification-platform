package com.notificationplatform.application.management;

import com.notificationplatform.application.common.ConflictException;
import com.notificationplatform.application.common.ResourceNotFoundException;
import com.notificationplatform.domain.entity.Product;
import com.notificationplatform.domain.repository.ProductRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductManagementService {

    private final ProductRepository productRepository;

    public ProductManagementService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public Product createProduct(CreateProductCommand command) {
        Objects.requireNonNull(command, "Create product command is required");
        String name = normalizeRequired(command.name(), "Product name is required");

        if (productRepository.existsByName(name)) {
            throw new ConflictException("Product already exists: " + name);
        }

        return productRepository.save(new Product(name));
    }

    @Transactional(readOnly = true)
    public Product getProduct(UUID productId) {
        Objects.requireNonNull(productId, "Product id is required");
        return productRepository.findById(productId)
            .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
    }

    @Transactional(readOnly = true)
    public List<Product> listProducts() {
        return productRepository.findAll();
    }

    private static String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
