package com.tameem.pricewatch.controller;


import com.tameem.pricewatch.dto.*;
import com.tameem.pricewatch.entity.ExchangeRate;
import com.tameem.pricewatch.entity.TrackedProduct;
import com.tameem.pricewatch.service.ExchangeRateService;
import com.tameem.pricewatch.service.PriceHistoryService;
import com.tameem.pricewatch.service.TrackedProductService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;

@RestController
@RequestMapping("/api")
public class ProductController {

//    private final ProductScraper scraper;
    private final TrackedProductService trackedProductService;
    private final ExchangeRateService exchangeRateService;
    private final PriceHistoryService priceHistoryService;

    public ProductController(TrackedProductService trackedProductService, ExchangeRateService exchangeRateService, PriceHistoryService priceHistoryService) {

        this.trackedProductService = trackedProductService;
        this.exchangeRateService = exchangeRateService;
        this.priceHistoryService = priceHistoryService;
    }


    /** POST /api/tracked-products — track a URL. 201 when newly scraped, 200 when already tracked. */
    @PostMapping("/tracked-products")
    public ResponseEntity<TrackedProductResponse> trackProduct(@RequestBody @Valid TrackRequest request) {
        TrackResult result = trackedProductService.trackProduct(request.url());
        // 201 when we scraped and inserted, 200 when this listing was already tracked.
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.product());
    }
    /** POST /api/tracked-products/{id}/refresh — re-scrape now and record a new price point. */
    @PostMapping("/tracked-products/{id}/refresh")
    public ResponseEntity<TrackedProductDetailResponse> refreshProduct(@PathVariable long id) {
        return ResponseEntity.ok(trackedProductService.reTrack(id));
    }

    /** GET /api/tracked-products — every tracked product, for the dashboard grid. */
    @GetMapping("/tracked-products")
    public List<TrackedProductResponse> getTrackedProducts() {
        return trackedProductService.getAllProducts();
    }
    /** GET /api/tracked-products/{id} — one product, for the detail page. */
    @GetMapping("/tracked-products/{id}")
    public TrackedProductDetailResponse getTrackedProduct(@PathVariable long id) {
        return trackedProductService.getProduct(id);
    }
    /** DELETE /api/tracked-products/{id} — stop tracking; cascades to listings and price points. 204. */
    @DeleteMapping("/tracked-products/{id}")
    public ResponseEntity<Void> deleteTrackedProducts(@PathVariable long id) {
        trackedProductService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/exchange-rates")
    public ResponseEntity<List<CurrencyResponse>>  getExchangeRate() {
        return ResponseEntity.ok(exchangeRateService.getCurrentExchangeRate());
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<LinkedHashMap<String, List<PricePointResponse>>> getProductHistory(@PathVariable long id) {
        TrackedProduct product = trackedProductService.getEntity(id);;
        return ResponseEntity.ok(
                priceHistoryService.getProductHistory(product)
        );
    }
}
