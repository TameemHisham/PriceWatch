package com.tameem.pricewatch.scraper;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The URL belongs to a storefront this deployment is not configured for.
 *
 * <p>A client error, not a server one: the request is well-formed but names a
 * marketplace we cannot price. Kept distinct from {@link ScrapeException} so a
 * bad paste returns 400 with a readable message instead of a 500 and a stack trace.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class UnsupportedMarketplaceException extends ScrapeException {
    public UnsupportedMarketplaceException(String message) {
        super(message);
    }
}
