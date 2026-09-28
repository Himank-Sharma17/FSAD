package com.shop.onlineshopping.dto;

import java.math.BigDecimal;

public record SalesLineResponse(String label, Long totalQuantity, BigDecimal totalRevenue) {}
