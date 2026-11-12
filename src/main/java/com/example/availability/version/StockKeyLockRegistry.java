package com.example.availability.version;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

@Component
public class StockKeyLockRegistry {

    private static final int LOCK_COUNT = 64;

    private final ReentrantLock[] locks = new ReentrantLock[LOCK_COUNT];

    public StockKeyLockRegistry() {
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    public ReentrantLock lockFor(StockKey key) {
        return locks[Math.floorMod(key.hashCode(), locks.length)];
    }
}
