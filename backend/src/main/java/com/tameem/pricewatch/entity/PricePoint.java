package com.tameem.pricewatch.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name="price_point", indexes = @Index(columnList = "product_listing_id, checked_at"))
public class PricePoint {

    @Id
    @GeneratedValue(strategy= GenerationType.AUTO)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_listing_id", nullable = false)
    private ProductListing productListingId;

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal price;
    @Column(nullable = false, name = "checked_at")
    private LocalDateTime checkedAt;

    public PricePoint() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ProductListing getProductListingId() {
        return productListingId;
    }

    public void setProductListingId(ProductListing productListingId) {
        this.productListingId = productListingId;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public LocalDateTime getCheckedAt() {
        return checkedAt;
    }

    public void setCheckedAt(LocalDateTime checkedAt) {
        this.checkedAt = checkedAt;
    }


}
