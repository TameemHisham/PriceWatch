package com.tameem.pricewatch.scraper;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class UnsupportedMarketplaceException extends ScrapeException {
    public UnsupportedMarketplaceException(String message) {
        super(message);
    }
}
