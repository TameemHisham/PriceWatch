package com.tameem.pricewatch.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;


@Entity
@Table(name="exchange_rate")
public class ExchangeRate {
    @Id
    private String currency;
    @Column(name="exchange_rate", nullable = false)
    private BigDecimal exchangeRate;
    @Column(nullable = false, name = "last_checked")
    private Instant lastChecked;

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getCurrency() {
        return currency;
    }


    public BigDecimal getExchangeRate() {
        return exchangeRate;
    }

    public void setExchangeRate(BigDecimal exchangeRate) {
        this.exchangeRate = exchangeRate;
    }

    public Instant getLastChecked() {
        return lastChecked;
    }
    @PreUpdate
    @PrePersist
    public void setLastChecked() {
        this.lastChecked = Instant.now();
    }
}
