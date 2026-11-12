package com.example.availability.version;

public record StockKey(
        String sellerId,
        String productId,
        String sizeId,
        String countryCode) {
}
