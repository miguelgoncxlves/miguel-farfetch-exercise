package com.example.availability.version;

import com.example.availability.api.SellerInventoryChanged;
import com.example.availability.model.CanonicalProductContext;
import com.example.availability.model.SizeAvailabilityUpdated;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record StockVersionState(
        long sourceVersion,
        StockProcessingPhase phase,
        SellerInventoryChanged inputEvent,
        CanonicalProductContext productContext,
        SizeAvailabilityUpdated outputEvent) {

    public static StockVersionState availabilityPending(
            SellerInventoryChanged inputEvent,
            CanonicalProductContext productContext,
            SizeAvailabilityUpdated outputEvent) {
        return new StockVersionState(
                inputEvent.sourceVersion(),
                StockProcessingPhase.AVAILABILITY_PENDING,
                inputEvent,
                productContext,
                outputEvent);
    }

    public StockVersionState publicationPending() {
        return new StockVersionState(
                sourceVersion,
                StockProcessingPhase.PUBLICATION_PENDING,
                inputEvent,
                productContext,
                outputEvent);
    }

    public StockVersionState complete() {
        return new StockVersionState(
                sourceVersion, StockProcessingPhase.COMPLETE, null, null, null);
    }

    public StockVersionState failed() {
        StockProcessingPhase failedPhase = switch (phase) {
            case AVAILABILITY_PENDING -> StockProcessingPhase.AVAILABILITY_FAILED;
            case PUBLICATION_PENDING -> StockProcessingPhase.PUBLICATION_FAILED;
            default -> throw new IllegalStateException("Only a pending operation can fail.");
        };
        return new StockVersionState(
                sourceVersion, failedPhase, inputEvent, productContext, outputEvent);
    }

    public StockVersionState retry() {
        StockProcessingPhase pendingPhase = switch (phase) {
            case AVAILABILITY_FAILED -> StockProcessingPhase.AVAILABILITY_PENDING;
            case PUBLICATION_FAILED -> StockProcessingPhase.PUBLICATION_PENDING;
            default -> throw new IllegalStateException("Only a failed operation can be retried.");
        };
        return new StockVersionState(
                sourceVersion, pendingPhase, inputEvent, productContext, outputEvent);
    }

    @JsonIgnore
    public boolean isPending() {
        return phase == StockProcessingPhase.AVAILABILITY_PENDING
                || phase == StockProcessingPhase.PUBLICATION_PENDING;
    }

    @JsonIgnore
    public boolean isFailed() {
        return phase == StockProcessingPhase.AVAILABILITY_FAILED
                || phase == StockProcessingPhase.PUBLICATION_FAILED;
    }
}
