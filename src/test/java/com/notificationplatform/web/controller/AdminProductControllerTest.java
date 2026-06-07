package com.notificationplatform.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notificationplatform.application.common.ConflictException;
import com.notificationplatform.application.management.ProductManagementService;
import com.notificationplatform.domain.entity.Product;
import com.notificationplatform.web.error.GlobalExceptionHandler;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminProductController.class)
@Import(GlobalExceptionHandler.class)
class AdminProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductManagementService productManagementService;

    @Test
    void listProductsReturnsProducts() throws Exception {
        Product product = product("Billing");
        when(productManagementService.listProducts()).thenReturn(List.of(product));

        mockMvc.perform(get("/api/v1/admin/products"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(product.getId().toString()))
            .andExpect(jsonPath("$[0].name").value("Billing"))
            .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void createProductReturnsCreatedProduct() throws Exception {
        Product product = product("Billing");
        when(productManagementService.createProduct(any())).thenReturn(product);

        mockMvc.perform(post("/api/v1/admin/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "Billing"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(product.getId().toString()))
            .andExpect(jsonPath("$.name").value("Billing"));
    }

    @Test
    void createProductReturnsBadRequestForBlankName() throws Exception {
        mockMvc.perform(post("/api/v1/admin/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": ""
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Request validation failed"));
    }

    @Test
    void createProductReturnsConflictForDuplicateName() throws Exception {
        when(productManagementService.createProduct(any()))
            .thenThrow(new ConflictException("Product already exists: Billing"));

        mockMvc.perform(post("/api/v1/admin/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "Billing"
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Product already exists: Billing"));
    }

    private static Product product(String name) {
        Product product = new Product(name);
        ReflectionTestUtils.setField(product, "id", UUID.randomUUID());
        return product;
    }
}
