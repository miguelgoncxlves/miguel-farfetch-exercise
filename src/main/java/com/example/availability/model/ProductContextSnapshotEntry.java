package com.example.availability.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductContextSnapshotEntry(
        String productId,
        String productName,
        ProductContext.Brand brand,
        String productStatus,
        String sizeId,
        String sizeLabel,
        String sizeSystem,
        String countryCode,
        boolean countryEnabled) {

    public ProductContext context() {
        return new ProductContext(
                productName, brand, productStatus, sizeLabel, sizeSystem, countryEnabled);
    }
}
