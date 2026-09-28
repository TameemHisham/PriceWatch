package com.tameem.pricewatch.controller;

import com.tameem.pricewatch.config.MarketplaceRegistry;
import com.tameem.pricewatch.dto.ScraperStatusResponse;
import com.tameem.pricewatch.scraper.ScraperRateLimiter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only visibility into the scraper layer for any authenticated user: which marketplaces are
 * enabled and the state of each one's self-healing circuit breaker. Everything under {@code /api}
 * (except {@code /api/auth}) already requires a token, so no extra authorization is needed here.
 */
@RestController
@RequestMapping("/api/scrapers")
public class ScraperStatusController {

    private final MarketplaceRegistry marketplaces;
    private final ScraperRateLimiter rateLimiter;

    public ScraperStatusController(MarketplaceRegistry marketplaces, ScraperRateLimiter rateLimiter) {
        this.marketplaces = marketplaces;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/status")
    public ScraperStatusResponse status() {
        List<ScraperStatusResponse.Marketplace> entries = new ArrayList<>();
        for (String id : marketplaces.allMarketplaceIds()) {
            ScraperRateLimiter.BreakerStatus breaker = rateLimiter.snapshot(id);
            entries.add(new ScraperStatusResponse.Marketplace(
                    id,
                    marketplaces.isEnabled(id),
                    breaker.state().name(),
                    breaker.openedAt(),
                    breaker.nextProbeAt(),
                    shortReason(breaker.lastError())));
        }
        return new ScraperStatusResponse(entries);
    }

    /** Strips any URL and collapses whitespace so a breaker reason never leaks a target URL or
     *  proxy endpoint, and caps the length for a compact response. */
    private static String shortReason(String lastError) {
        if (lastError == null) {
            return null;
        }
        String cleaned = lastError.replaceAll("https?://\\S+", "").replaceAll("\\s+", " ").trim();
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }
}
