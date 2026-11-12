package com.example.availability.service;

import com.example.availability.api.SellerInventoryChanged;
import com.example.availability.client.AvailabilityClient;
import com.example.availability.client.OutputEventPublisher;
import com.example.availability.client.ProductContextClient;
import com.example.availability.model.CanonicalProductContext;
import com.example.availability.model.ProductContext;
import com.example.availability.model.SizeAvailabilityUpdated;
import com.example.availability.version.StockProcessingPhase;
import com.example.availability.version.StockKey;
import com.example.availability.version.StockKeyLockRegistry;
import com.example.availability.version.StockVersionStore;
import com.example.availability.version.StockVersionState;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class AvailabilityProcessingService {

    private static final Logger logger =
            LoggerFactory.getLogger(AvailabilityProcessingService.class);

    private final ProductContextClient productContextClient;
    private final AvailabilityClient availabilityClient;
    private final OutputEventPublisher outputEventPublisher;
    private final StockVersionStore versionStore;
    private final StockKeyLockRegistry lockRegistry;
    private final Clock clock;

    public AvailabilityProcessingService(
            ProductContextClient productContextClient,
            AvailabilityClient availabilityClient,
            OutputEventPublisher outputEventPublisher,
            StockVersionStore versionStore,
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
        return process(
                event,
                () -> productContextClient.get(
                        event.productId(), event.sizeId(), event.countryCode()));
    }

    public ProcessingResult process(
            SellerInventoryChanged event, Supplier<ProductContext> contextSupplier) {
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
                if (event.sourceVersion() < state.sourceVersion()) {
                    return ProcessingResult.skipped("stale_version");
                }
                if (state.isPending()) {
                    finishPendingOperation(key, state);
                    if (event.sourceVersion() == state.sourceVersion()) {
                        return ProcessingResult.processed();
                    }
                } else if (state.isFailed()) {
                    state = state.retry();
                    versionStore.save(key, state);
                    finishPendingOperation(key, state);
                    if (event.sourceVersion() == state.sourceVersion()) {
                        return ProcessingResult.processed();
                    }
                } else if (event.sourceVersion() == state.sourceVersion()) {
                    return ProcessingResult.skipped("duplicate_version");
                }
            }

            ProductContext context = contextSupplier.get();
            if (!context.isActiveAndCountryEnabled()) {
                throw new ProductContextNotAvailableException(
                        "The product is inactive or is not enabled for the requested country.");
            }

            CanonicalProductContext canonicalContext = context.canonical();
            SizeAvailabilityUpdated outputEvent = toOutputEvent(event, canonicalContext);
            StockVersionState pendingState =
                    StockVersionState.availabilityPending(event, canonicalContext, outputEvent);
            versionStore.save(key, pendingState);
            finishPendingOperation(key, pendingState);
            return ProcessingResult.processed();
        } finally {
            lock.unlock();
        }
    }

    public void recoverPendingOperations() {
        for (StockKey key : versionStore.pendingKeys()) {
            ReentrantLock lock = lockRegistry.lockFor(key);
            lock.lock();
            try {
                StockVersionState state = versionStore.state(key).orElse(null);
                if (state != null && state.isPending()) {
                    finishPendingOperation(key, state);
                }
            } catch (RestClientException exception) {
                logger.warn(
                        "Could not recover pending stock operation for {} at version {}.",
                        key,
                        versionStore.state(key).map(StockVersionState::sourceVersion).orElse(-1L),
                        exception);
            } finally {
                lock.unlock();
            }
        }
    }

    private void finishPendingOperation(StockKey key, StockVersionState state) {
        if (state.phase() == StockProcessingPhase.AVAILABILITY_PENDING) {
            try {
                availabilityClient.update(state.inputEvent(), state.productContext());
            } catch (RestClientResponseException exception) {
                savePermanentFailure(key, state, exception);
                throw exception;
            }
            state = state.publicationPending();
            versionStore.save(key, state);
        }
        if (state.phase() == StockProcessingPhase.PUBLICATION_PENDING) {
            try {
                outputEventPublisher.publish(state.outputEvent());
            } catch (RestClientResponseException exception) {
                savePermanentFailure(key, state, exception);
                throw exception;
            }
            versionStore.save(key, state.complete());
        }
    }

    private void savePermanentFailure(
            StockKey key, StockVersionState state, RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        if (status != 429 && status != 503) {
            versionStore.save(key, state.failed());
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
