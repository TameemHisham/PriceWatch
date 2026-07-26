package com.tameem.pricewatch.controller;


import com.tameem.pricewatch.dto.TrackedProductResponse;
import com.tameem.pricewatch.dto.TrackRequest;
import com.tameem.pricewatch.service.TrackedProductService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ProductController {

//    private final ProductScraper scraper;
    private final TrackedProductService trackedProductService;
    public ProductController(TrackedProductService trackedProductService) {

        this.trackedProductService = trackedProductService;
    }


    @PostMapping("/tracked-products")
    public ResponseEntity<TrackedProductResponse> trackProduct(@RequestBody @Valid TrackRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(trackedProductService.trackProduct(request.url()));
    }
    @PostMapping("/tracked-products/{id}/refresh")
    public ResponseEntity<TrackedProductResponse> refreshProduct(@PathVariable long id) {
        return ResponseEntity.ok(trackedProductService.reTrack(id));
    }

    @GetMapping("/tracked-products")
    public List<TrackedProductResponse> getTrackedProducts() {
        return trackedProductService.getAllProducts();
    }
    @GetMapping("/tracked-products/{id}")
    public TrackedProductResponse getTrackedProduct(@PathVariable long id) {
        return trackedProductService.getProduct(id);
    }
    @DeleteMapping("/tracked-products/{id}")
    public ResponseEntity<Void> deleteTrackedProducts(@PathVariable long id) {
        trackedProductService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }
}
