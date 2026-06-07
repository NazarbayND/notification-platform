package com.notificationplatform.web.controller;

import com.notificationplatform.application.management.CreateProductCommand;
import com.notificationplatform.application.management.ProductManagementService;
import com.notificationplatform.web.dto.CreateProductRequest;
import com.notificationplatform.web.dto.ProductResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/products")
public class AdminProductController {

    private final ProductManagementService productManagementService;

    public AdminProductController(ProductManagementService productManagementService) {
        this.productManagementService = productManagementService;
    }

    @GetMapping
    public List<ProductResponse> listProducts() {
        return productManagementService.listProducts().stream()
            .map(ProductResponse::from)
            .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse createProduct(@Valid @RequestBody CreateProductRequest request) {
        return ProductResponse.from(productManagementService.createProduct(new CreateProductCommand(request.name())));
    }
}
