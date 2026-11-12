package com.example.availability.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

public record SizeAvailabilityUpdated(
        String eventId,
        String eventType,
        int schemaVersion,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
        String sellerId,
        String productId,
        String sizeId,
        String countryCode,
        int availableQuantity,
        long sourceVersion,
        CanonicalProductContext productContext) {
}
