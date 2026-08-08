package com.tameem.pricewatch.scraper;

import java.util.Optional;

public interface ProductScraper {
    public ProductData scrape(String url);
    Optional<String> productKey(String url);

}
