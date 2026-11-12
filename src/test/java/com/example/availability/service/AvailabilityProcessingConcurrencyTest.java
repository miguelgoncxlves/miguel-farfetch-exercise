package com.example.availability.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.availability.api.SellerInventoryChanged;
import com.example.availability.client.AvailabilityClient;
import com.example.availability.client.OutputEventPublisher;
import com.example.availability.client.ProductContextClient;
import com.example.availability.model.ProductContext;
import com.example.availability.version.InMemoryStockVersionStore;
import com.example.availability.version.StockKeyLockRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AvailabilityProcessingConcurrencyTest {

    @Mock
    private ProductContextClient productContextClient;

    @Mock
    private AvailabilityClient availabilityClient;

    @Mock
    private OutputEventPublisher outputEventPublisher;

    @Test
    void newerVersionWinsWhenAnOlderUpdateArrivesWhileItIsProcessing() throws Exception {
        CountDownLatch productContextLookupStarted = new CountDownLatch(1);
        CountDownLatch allowProductContextLookupToFinish = new CountDownLatch(1);
        ProductContext context = new ProductContext(
                "Linen Shirt 123",
                new ProductContext.Brand("brand-42", "Atelier Example"),
                "ACTIVE",
                "EU 38",
                "EU",
                true);
        when(productContextClient.get(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    productContextLookupStarted.countDown();
                    if (!allowProductContextLookupToFinish.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release context lookup.");
                    }
                    return context;
                });

        AvailabilityProcessingService processingService = new AvailabilityProcessingService(
                productContextClient,
                availabilityClient,
                outputEventPublisher,
                new InMemoryStockVersionStore(),
                new StockKeyLockRegistry(),
                Clock.systemUTC());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var newerUpdate = executor.submit(
                    () -> processingService.process(event(8L, "newer-message")));
            assertThat(productContextLookupStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var olderUpdate = executor.submit(
                    () -> processingService.process(event(7L, "older-message")));
            allowProductContextLookupToFinish.countDown();

            assertThat(newerUpdate.get(5, TimeUnit.SECONDS))
                    .isEqualTo(ProcessingResult.processed());
            assertThat(olderUpdate.get(5, TimeUnit.SECONDS))
                    .isEqualTo(ProcessingResult.skipped("stale_version"));
        } finally {
            allowProductContextLookupToFinish.countDown();
            executor.shutdownNow();
        }

        verify(productContextClient, times(1)).get("style-123", "size-38", "PT");
        verify(availabilityClient, times(1)).update(any(SellerInventoryChanged.class), any());
        verify(outputEventPublisher, times(1)).publish(any());
    }

    private SellerInventoryChanged event(long sourceVersion, String eventId) {
        return new SellerInventoryChanged(
                eventId,
                "SellerInventoryChanged",
                1,
                Instant.parse("2026-10-20T08:00:00Z"),
                "boutique-42",
                "style-123",
                "size-38",
                "PT",
                3,
                sourceVersion);
    }
}
