package com.example.availability.model;

public record AvailabilityUpdate(
        int availableQuantity,
        long sourceVersion,
        CanonicalProductContext productContext) {
}
