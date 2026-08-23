package com.tameem.pricewatch.entity;


import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "product_listing", uniqueConstraints = {
//        @UniqueConstraint(columnNames = {"tracked_product_id", "store"}),
        @UniqueConstraint(name = "uk_product_listing_url", columnNames = {"url"}),
        @UniqueConstraint(name="UniqueProductMarketPlace",columnNames ={"tracked_product_id", "marketplace"})
//        this basically applies UNIQUE property on a composite key
})
public class ProductListing {
    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) // many product listings to one product
    @JoinColumn(name = "tracked_product_id", nullable = false)
    private TrackedProduct trackedProduct;
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Store store;
    @Column(length = 2083, nullable = false)
    private String url;
    @Column(nullable = false, length = 40)
    private String marketplace;
    @Column(length = 50, nullable = false)
    private String currency;
    @Column(nullable = true, name = "last_checked")
    private Instant lastChecked;
    @Column(nullable = false, name = "created_at")
    private Instant createdAt;

    public ProductListing() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public TrackedProduct getTrackedProduct() {
        return trackedProduct;
    }

    public void setTrackedProduct(TrackedProduct trackedProduct) {
        this.trackedProduct = trackedProduct;
    }

    public String getMarketplace() {
        return marketplace;
    }

    public void setMarketplace(String marketplace) {
        this.marketplace = marketplace;
    }

    public Store getStore() {
        return store;
    }

    public void setStore(Store store) {
        this.store = store;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Instant getLastChecked() {
        return lastChecked;
    }

    public void setLastChecked(Instant lastChecked) {
        this.lastChecked = lastChecked;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    @PrePersist
    public void onPrePersist() {
        this.setCreatedAt(Instant.now());
    }


}
