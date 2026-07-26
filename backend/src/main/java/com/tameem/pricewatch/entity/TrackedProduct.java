package com.tameem.pricewatch.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name="tracked_product")
public class TrackedProduct {
    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private Long id;
    @Column(length = 300, nullable = false)
    private String name;
    @Column(length = 50, nullable = true)
    private String brand;
    @Column(length = 50, nullable = true)
    private String category;
    @Column(precision = 10, scale = 2, nullable = true, name = "target_price")
    private BigDecimal targetPrice;
    @Column(nullable = false, name = "created_at")
    private Instant createdAt;
    @Column(length = 2083, nullable = true, name = "image_url")
    private String imageUrl;

    public TrackedProduct() {}

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public BigDecimal getTargetPrice() {
        return targetPrice;
    }

    public void setTargetPrice(BigDecimal targetPrice) {
        this.targetPrice = targetPrice;
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
