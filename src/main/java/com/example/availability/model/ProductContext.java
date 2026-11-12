package com.example.availability.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductContext(
        String productName,
        Brand brand,
        String productStatus,
        String sizeLabel,
        String sizeSystem,
        boolean countryEnabled) {

    public boolean isActiveAndCountryEnabled() {
        return "ACTIVE".equalsIgnoreCase(productStatus) && countryEnabled;
    }

    public CanonicalProductContext canonical() {
        return new CanonicalProductContext(productName, brand, sizeLabel, sizeSystem);
    }

    public record Brand(String id, String name) {
    }
}
