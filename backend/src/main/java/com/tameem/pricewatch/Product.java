package com.tameem.pricewatch;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name="products")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private String store;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;
    @Column(name= "last_checked" ,nullable = false)
    private LocalDateTime lastChecked;

    public Product() {}

    public Product(Long id, String name, String store, BigDecimal price, LocalDateTime lastChecked) {
        this.id = id;
        this.name = name;
        this.store = store;
        this.price = price;
        this.lastChecked = lastChecked;
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

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public LocalDateTime getLastChecked() {
        return lastChecked;
    }

    public void setLastChecked(LocalDateTime lastChecked) {
        this.lastChecked = lastChecked;
    }

}