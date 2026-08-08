package com.tameem.pricewatch.dto;

import com.tameem.pricewatch.entity.Store;

import java.math.BigDecimal;

public record ListingResponse (Store store, String url, String currency, BigDecimal currentPrice)
{
}


