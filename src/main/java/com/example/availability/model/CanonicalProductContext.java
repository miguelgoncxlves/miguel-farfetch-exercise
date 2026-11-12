package com.example.availability.model;

public record CanonicalProductContext(
        String productName,
        ProductContext.Brand brand,
        String sizeLabel,
        String sizeSystem) {
}
