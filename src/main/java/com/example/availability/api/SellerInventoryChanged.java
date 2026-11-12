package com.example.availability.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;

public record SellerInventoryChanged(
        @NotBlank String eventId,
        @NotBlank String eventType,
        @NotNull @Min(1) Integer schemaVersion,
        @NotNull Instant occurredAt,
        @NotBlank String sellerId,
        @NotBlank String productId,
        @NotBlank String sizeId,
        @NotBlank @Pattern(regexp = "[A-Z]{2}") String countryCode,
        @NotNull @PositiveOrZero Integer availableQuantity,
        @NotNull @PositiveOrZero Long sourceVersion) {
}
