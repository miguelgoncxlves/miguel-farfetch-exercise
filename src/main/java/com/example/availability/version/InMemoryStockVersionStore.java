package com.example.availability.version;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Optional;

public class InMemoryStockVersionStore implements StockVersionStore {

    private final ConcurrentMap<StockKey, StockVersionState> states = new ConcurrentHashMap<>();

    @Override
    public Optional<StockVersionState> state(StockKey key) {
        return Optional.ofNullable(states.get(key));
    }

    @Override
    public void save(StockKey key, StockVersionState state) {
        states.put(key, state);
    }

    @Override
    public List<StockKey> pendingKeys() {
        return states.entrySet().stream()
                .filter(entry -> entry.getValue().isPending())
                .map(entry -> entry.getKey())
                .toList();
    }
}
