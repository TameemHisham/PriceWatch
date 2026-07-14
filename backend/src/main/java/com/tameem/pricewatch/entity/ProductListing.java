package com.tameem.pricewatch.entity;


import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "product_listing", uniqueConstraints = @UniqueConstraint(columnNames = {"tracked_product_id", "store"}))
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
    @Column(length = 50, nullable = false)
    private String currency;
    @Column(nullable = true, name = "last_checked")
    private LocalDateTime lastChecked;
    @Column(nullable = false, name = "created_at")
    private LocalDateTime createdAt;

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

    public LocalDateTime getLastChecked() {
        return lastChecked;
    }

    public void setLastChecked(LocalDateTime lastChecked) {
        this.lastChecked = lastChecked;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    @PrePersist
    public void onPrePersist() {
        this.setCreatedAt(LocalDateTime.now());
    }


}
