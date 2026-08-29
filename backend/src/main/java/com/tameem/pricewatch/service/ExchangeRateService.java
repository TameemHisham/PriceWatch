package com.tameem.pricewatch.service;

import com.tameem.pricewatch.dto.CurrencyResponse;
import com.tameem.pricewatch.entity.ExchangeRate;
import com.tameem.pricewatch.repositories.ExchangeRateRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ExchangeRateService {
    private final ExchangeRateRepository exchangeRateRepository;

    public ExchangeRateService(ExchangeRateRepository exchangeRateRepository) {
        this.exchangeRateRepository = exchangeRateRepository;
    }

    public List<CurrencyResponse> getCurrentExchangeRate() {
        List<ExchangeRate> currencies = exchangeRateRepository.findAll();
        return currencies.stream()
                .map(c -> new CurrencyResponse(c.getCurrency(), c.getExchangeRate()))
                .toList();
    }

    /** Builds the ASIN -> USD rate lookup once per call, so a comparison loop doesn't hit the DB per listing. */
    public Map<String, BigDecimal> currentRatesByCurrency() {
        return exchangeRateRepository.findAll().stream()
                .collect(Collectors.toMap(ExchangeRate::getCurrency, ExchangeRate::getExchangeRate));
    }

    public BigDecimal convertToUsd(BigDecimal price, String currency, Map<String, BigDecimal> rates) {
        if (price == null || currency == null) return null;
        BigDecimal exchangeRate = rates.get(currency);
        if (exchangeRate == null) return null;
        return price.divide(exchangeRate, 2, RoundingMode.HALF_UP);
    }
}