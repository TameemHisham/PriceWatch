package com.tameem.pricewatch.dto;

import java.time.Instant;
import java.util.List;

/**
 * Read-only health of the scraper layer, per marketplace: whether it is manually enabled and
 * the state of its self-healing circuit breaker. Deliberately carries no URLs and no proxy
 * detail — {@code lastError} is a short reason string only.
 */
public record ScraperStatusResponse(List<Marketplace> marketplaces) {

    public record Marketplace(
            String marketplace,
            boolean enabled,
            String breakerState,
            Instant openedAt,
            Instant nextProbeAt,
            String lastError) {}
}
