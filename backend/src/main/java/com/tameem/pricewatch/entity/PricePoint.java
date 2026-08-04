package com.tameem.pricewatch.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
//import java.time.LocalDateTime;

@Entity
@Table(name="price_point", indexes = @Index(columnList = "product_listing_id, checked_at"))
public class PricePoint {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_listing_id", nullable = false)
    private ProductListing productListing;

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal price;
    @Column(nullable = false, name = "checked_at")
    private Instant checkedAt;

    public PricePoint() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ProductListing getProductListing() {
        return productListing;
    }

    public void setProductListing(ProductListing productListing) {
        this.productListing = productListing;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

    public void setCheckedAt(Instant checkedAt) {
        this.checkedAt = checkedAt;
    }


}
