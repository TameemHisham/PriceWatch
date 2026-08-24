package com.tameem.pricewatch.dto;

import java.math.BigDecimal;

public record CurrencyResponse ( String quote, BigDecimal rate) {}
