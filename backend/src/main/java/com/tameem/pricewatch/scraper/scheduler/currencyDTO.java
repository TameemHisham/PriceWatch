package com.tameem.pricewatch.scraper.scheduler;

import java.math.BigDecimal;

public record currencyDTO(String date , String base, String quote, BigDecimal rate) {}
