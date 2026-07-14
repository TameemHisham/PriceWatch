package com.tameem.pricewatch.repositories;

import com.tameem.pricewatch.entity.ProductListing;
import com.tameem.pricewatch.entity.TrackedProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductListingRepository extends JpaRepository<ProductListing, Long> {
    List<ProductListing> findByTrackedProduct(TrackedProduct trackedProduct);
}
