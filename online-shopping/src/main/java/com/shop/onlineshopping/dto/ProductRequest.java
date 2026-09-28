package com.shop.onlineshopping.dto;

import com.shop.onlineshopping.entity.Category;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ProductRequest(
        @NotBlank String name,
        @Size(max = 1000) String description,
        @NotNull @DecimalMin(value = "0.0", inclusive = false)
        @Digits(integer = 10, fraction = 2) BigDecimal price,
        @NotNull @Min(0) Integer qoh,
        @NotNull Category category
) {}
