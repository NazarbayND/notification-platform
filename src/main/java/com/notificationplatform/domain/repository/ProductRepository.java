package com.notificationplatform.domain.repository;

import com.notificationplatform.domain.entity.Product;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    boolean existsByName(String name);

    Optional<Product> findByName(String name);
}
