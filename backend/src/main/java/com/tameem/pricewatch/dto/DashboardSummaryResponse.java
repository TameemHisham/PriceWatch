package com.tameem.pricewatch.dto;

import java.math.BigDecimal;

public record DashboardSummaryResponse(int trackedCount, int drops, BigDecimal savings, BigDecimal avgDrop) {}
