package com.tameem.pricewatch.repositories;

import com.tameem.pricewatch.entity.ProductListing;
import com.tameem.pricewatch.entity.TrackedProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProductListingRepository extends JpaRepository<ProductListing, Long> {
    /** All store listings belonging to one tracked product. */
    List<ProductListing> findByTrackedProduct(TrackedProduct trackedProduct);
    /** Finds a listing by its normalised URL — the duplicate check on track. */
    Optional<ProductListing> findByUrl(String url);


    List<ProductListing> findByLastCheckedBeforeOrLastCheckedIsNull(Instant lastChecked);

    Optional<ProductListing> findByUrlContaining(String url);
}
