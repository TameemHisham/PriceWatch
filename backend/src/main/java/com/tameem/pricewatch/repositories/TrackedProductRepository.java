package com.tameem.pricewatch.repositories;

import com.tameem.pricewatch.entity.TrackedProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrackedProductRepository extends JpaRepository<TrackedProduct, Long> {
    List<TrackedProduct> findByUserId(Long userId);

}
