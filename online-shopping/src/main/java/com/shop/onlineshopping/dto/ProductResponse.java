package com.shop.onlineshopping.dto;

import com.shop.onlineshopping.entity.Category;
import com.shop.onlineshopping.entity.Product;

import java.math.BigDecimal;

public record ProductResponse(
        Long id,
        String name,
        String description,
        BigDecimal price,
        Integer qoh,
        Category category
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getQoh(),
                product.getCategory());
    }
}
