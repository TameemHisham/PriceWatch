package com.tameem.pricewatch.repositories;

import com.tameem.pricewatch.entity.PricePoint;
import com.tameem.pricewatch.entity.ProductListing;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PricePointRepository extends JpaRepository<PricePoint,Long> {
    void deleteByProductListing(ProductListing productListing);
}
