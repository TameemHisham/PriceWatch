package com.tameem.pricewatch.repositories;

import com.tameem.pricewatch.entity.TrackedProduct;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrackedProductRepository extends JpaRepository<TrackedProduct, Long> {}
