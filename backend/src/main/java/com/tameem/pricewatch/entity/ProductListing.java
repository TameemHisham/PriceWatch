package com.tameem.pricewatch.entity;


import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "product_listing", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"tracked_product_id", "store"}),
        // URLs are normalised before saving, so this is the real duplicate guard —
        // it closes the race window in the service's check-then-act.
        @UniqueConstraint(name = "uk_product_listing_url", columnNames = {"url"})
})
public class ProductListing {
    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tracked_product_id", nullable = false)
    private TrackedProduct trackedProduct;
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Store store;
    @Column(length = 2083, nullable = false)
    private String url;
    /**
     * Id of the configured marketplace this listing is priced for, e.g. AMAZON_UK.
     * Text rather than an enum because marketplaces are configuration — adding a
     * storefront should not need a rebuild. Nullable only because rows predate it.
     */
    @Column(length = 40)
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
