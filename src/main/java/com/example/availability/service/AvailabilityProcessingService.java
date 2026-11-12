package com.example.availability.service;

import com.example.availability.api.SellerInventoryChanged;
import com.example.availability.client.AvailabilityClient;
import com.example.availability.client.OutputEventPublisher;
import com.example.availability.client.ProductContextClient;
import com.example.availability.model.CanonicalProductContext;
import com.example.availability.model.ProductContext;
import com.example.availability.model.SizeAvailabilityUpdated;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AvailabilityProcessingService {

    private final ProductContextClient productContextClient;
    private final AvailabilityClient availabilityClient;
    private final OutputEventPublisher outputEventPublisher;
    private final Clock clock;

    public AvailabilityProcessingService(
            ProductContextClient productContextClient,
            AvailabilityClient availabilityClient,
            OutputEventPublisher outputEventPublisher,
            Clock clock) {
        this.productContextClient = productContextClient;
        this.availabilityClient = availabilityClient;
        this.outputEventPublisher = outputEventPublisher;
        this.clock = clock;
    }

    public void process(SellerInventoryChanged event) {
        if (!"SellerInventoryChanged".equals(event.eventType()) || event.schemaVersion() != 1) {
            throw new InvalidInventoryEventException(
                    "Only SellerInventoryChanged schema version 1 is supported.");
        }

        ProductContext context = productContextClient.get(
                event.productId(), event.sizeId(), event.countryCode());
        if (!context.isActiveAndCountryEnabled()) {
            throw new ProductContextNotAvailableException(
                    "The product is inactive or is not enabled for the requested country.");
        }

        CanonicalProductContext canonicalContext = context.canonical();
        availabilityClient.update(event, canonicalContext);
        outputEventPublisher.publish(toOutputEvent(event, canonicalContext));
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
