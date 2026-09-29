package com.shop.onlineshopping.dto;

public record LowStockEvent(Long productId, String name, int qoh) {
}
