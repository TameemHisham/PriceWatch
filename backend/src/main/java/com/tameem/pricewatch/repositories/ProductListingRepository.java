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
    /** Finds one caller's listing by normalised URL — the duplicate check on track.
     *  Scoped to the owner: an unscoped lookup returned other users' products. */
    Optional<ProductListing> findByUrlAndTrackedProduct_User_Id(String url, Long userId);


    List<ProductListing> findByLastCheckedBeforeOrLastCheckedIsNull(Instant lastChecked);

    /** Finds one caller's listing whose URL carries this product id, for cross-marketplace
     *  matching. Scoped to the owner for the same reason as above. */
    Optional<ProductListing> findByUrlContainingAndTrackedProduct_User_Id(String url, Long userId);

    /** The listing this product already holds on one marketplace, if any.
     *  Mirrors the (tracked_product_id, marketplace) unique constraint, so callers can check
     *  before inserting rather than letting the database reject it. Needs no user scoping:
     *  the product is already resolved, and listings never span owners. */
    Optional<ProductListing> findByTrackedProductAndMarketplace(TrackedProduct trackedProduct,
                                                                String marketplace);
}
