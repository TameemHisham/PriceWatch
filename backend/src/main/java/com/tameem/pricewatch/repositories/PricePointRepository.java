package com.tameem.pricewatch.repositories;

import com.tameem.pricewatch.entity.PricePoint;
import com.tameem.pricewatch.entity.ProductListing;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PricePointRepository extends JpaRepository<PricePoint,Long> {
    /** Removes every price observation for a listing — called before deleting the listing. */
    void deleteByProductListing(ProductListing productListing);

    /** The most recent price observation for a listing, i.e. its current price. */
    PricePoint findTopByProductListingOrderByCheckedAtDesc(ProductListing productListing);
}
