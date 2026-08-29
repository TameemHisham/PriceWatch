package com.tameem.pricewatch.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PricePointResponse(Instant checkedAt, BigDecimal price, String currency) {
}
