package com.tameem.pricewatch.dto;

/**
 *  it reports whether a
 * product was created or already exits, rather than choosing a status code. The controller translates
 * {@code created} into 201 vs 200.
 */
public record TrackResult(TrackedProductResponse product, boolean created) {}
