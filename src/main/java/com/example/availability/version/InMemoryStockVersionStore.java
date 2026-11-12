package com.example.availability.version;

import com.example.availability.model.SizeAvailabilityUpdated;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class InMemoryStockVersionStore {

    private final ConcurrentMap<StockKey, StockVersionState> states = new ConcurrentHashMap<>();

    public Optional<StockVersionState> state(StockKey key) {
        return Optional.ofNullable(states.get(key));
    }

    public void markAvailabilityApplied(
            StockKey key, long sourceVersion, SizeAvailabilityUpdated outputEvent) {
        states.put(key, new StockVersionState(sourceVersion, outputEvent));
    }

    public void markOutputPublished(StockKey key, long sourceVersion) {
        states.compute(key, (ignored, current) -> {
            if (current == null || current.latestAppliedVersion() != sourceVersion) {
                throw new IllegalStateException("Stock version state changed during processing.");
            }
            return new StockVersionState(sourceVersion, null);
        });
    }
}
