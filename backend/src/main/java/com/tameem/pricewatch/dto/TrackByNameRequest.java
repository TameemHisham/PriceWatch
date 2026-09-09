package com.tameem.pricewatch.dto;

import jakarta.validation.constraints.NotBlank;

/** Track by product name rather than a URL — the store is discovered, not supplied. */
public record TrackByNameRequest(@NotBlank String name) {}
