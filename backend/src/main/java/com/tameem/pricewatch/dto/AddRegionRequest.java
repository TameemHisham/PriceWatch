package com.tameem.pricewatch.dto;

import jakarta.validation.constraints.NotBlank;

/** Body for POST /api/tracked-products/{id}/regions — the storefront to opt this product into. */
public record AddRegionRequest(@NotBlank String marketplaceId) {}
