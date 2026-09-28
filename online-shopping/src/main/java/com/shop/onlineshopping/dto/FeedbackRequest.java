package com.shop.onlineshopping.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record FeedbackRequest(
        @NotBlank String message,
        @NotNull @Min(1) @Max(5) Integer rating
) {}
