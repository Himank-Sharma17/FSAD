package com.shop.onlineshopping.dto;

import com.shop.onlineshopping.entity.Category;

import java.math.BigDecimal;

public record CategorySalesRow(Category category, Long totalQuantity, BigDecimal totalRevenue) {}
