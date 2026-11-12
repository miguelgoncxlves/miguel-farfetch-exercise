package com.example.availability.version;

import java.util.List;
import java.util.Optional;

public interface StockVersionStore {

    Optional<StockVersionState> state(StockKey key);

    void save(StockKey key, StockVersionState state);

    List<StockKey> pendingKeys();
}
