package com.example.availability.version;

import com.example.availability.model.SizeAvailabilityUpdated;

public record StockVersionState(
        long latestAppliedVersion,
        SizeAvailabilityUpdated pendingOutputEvent) {
}
