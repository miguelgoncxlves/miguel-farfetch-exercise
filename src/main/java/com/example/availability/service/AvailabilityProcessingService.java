package com.example.availability.service;

import com.example.availability.api.SellerInventoryChanged;
import com.example.availability.client.AvailabilityClient;
import com.example.availability.client.OutputEventPublisher;
import com.example.availability.client.ProductContextClient;
import com.example.availability.model.CanonicalProductContext;
import com.example.availability.model.ProductContext;
import com.example.availability.model.SizeAvailabilityUpdated;
import com.example.availability.version.InMemoryStockVersionStore;
import com.example.availability.version.StockKey;
import com.example.availability.version.StockKeyLockRegistry;
import com.example.availability.version.StockVersionState;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

@Service
public class AvailabilityProcessingService {

    private final ProductContextClient productContextClient;
    private final AvailabilityClient availabilityClient;
    private final OutputEventPublisher outputEventPublisher;
    private final InMemoryStockVersionStore versionStore;
    private final StockKeyLockRegistry lockRegistry;
    private final Clock clock;

    public AvailabilityProcessingService(
            ProductContextClient productContextClient,
            AvailabilityClient availabilityClient,
            OutputEventPublisher outputEventPublisher,
            InMemoryStockVersionStore versionStore,
            StockKeyLockRegistry lockRegistry,
            Clock clock) {
        this.productContextClient = productContextClient;
        this.availabilityClient = availabilityClient;
        this.outputEventPublisher = outputEventPublisher;
        this.versionStore = versionStore;
        this.lockRegistry = lockRegistry;
        this.clock = clock;
    }

    public ProcessingResult process(SellerInventoryChanged event) {
        if (!"SellerInventoryChanged".equals(event.eventType()) || event.schemaVersion() != 1) {
            throw new InvalidInventoryEventException(
                    "Only SellerInventoryChanged schema version 1 is supported.");
        }

        StockKey key = new StockKey(
                event.sellerId(), event.productId(), event.sizeId(), event.countryCode());
        ReentrantLock lock = lockRegistry.lockFor(key);
        lock.lock();
        try {
            StockVersionState state = versionStore.state(key).orElse(null);
            if (state != null) {
                if (event.sourceVersion() < state.latestAppliedVersion()) {
                    return ProcessingResult.skipped("stale_version");
                }
                if (state.pendingOutputEvent() != null) {
                    outputEventPublisher.publish(state.pendingOutputEvent());
                    versionStore.markOutputPublished(key, state.latestAppliedVersion());
                    if (event.sourceVersion() == state.latestAppliedVersion()) {
                        return ProcessingResult.processed();
                    }
                } else if (event.sourceVersion() == state.latestAppliedVersion()) {
                    return ProcessingResult.skipped("duplicate_version");
                }
            }

            ProductContext context = productContextClient.get(
                    event.productId(), event.sizeId(), event.countryCode());
            if (!context.isActiveAndCountryEnabled()) {
                throw new ProductContextNotAvailableException(
                        "The product is inactive or is not enabled for the requested country.");
            }

            CanonicalProductContext canonicalContext = context.canonical();
            availabilityClient.update(event, canonicalContext);
            SizeAvailabilityUpdated outputEvent = toOutputEvent(event, canonicalContext);
            versionStore.markAvailabilityApplied(key, event.sourceVersion(), outputEvent);
            outputEventPublisher.publish(outputEvent);
            versionStore.markOutputPublished(key, event.sourceVersion());
            return ProcessingResult.processed();
        } finally {
            lock.unlock();
        }
    }

    private SizeAvailabilityUpdated toOutputEvent(
            SellerInventoryChanged event, CanonicalProductContext productContext) {
        return new SizeAvailabilityUpdated(
                UUID.randomUUID().toString(),
                "SizeAvailabilityUpdated",
                1,
                clock.instant(),
                event.sellerId(),
                event.productId(),
                event.sizeId(),
                event.countryCode(),
                event.availableQuantity(),
                event.sourceVersion(),
                productContext);
    }
}
