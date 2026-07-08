package com.tameem.pricewatch.scraper;

public interface ProductScraper {
    String storeName();
    boolean supports(String url);
    ProductData scrape(String url);
}
