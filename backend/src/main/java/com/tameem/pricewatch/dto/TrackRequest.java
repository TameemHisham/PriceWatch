package com.tameem.pricewatch.dto;

import jakarta.validation.constraints.NotBlank;

public record TrackRequest(@NotBlank String url) {}
