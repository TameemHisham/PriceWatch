package com.tameem.pricewatch.repositories;

import com.tameem.pricewatch.entity.ExchangeRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, String> {
}
