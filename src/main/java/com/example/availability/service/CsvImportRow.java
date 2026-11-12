package com.example.availability.service;

record CsvImportRow(
        int rowNumber,
        String sellerId,
        String productId,
        String sizeId,
        String countryCode,
        String availableQuantity,
        String sourceVersion) {
}
