package com.example.availability.client;

import com.example.availability.api.SellerInventoryChanged;
import com.example.availability.model.AvailabilityUpdate;
import com.example.availability.model.CanonicalProductContext;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AvailabilityClient {

    private final RestClient restClient;
    private final DownstreamRetryExecutor retryExecutor;

    public AvailabilityClient(RestClient restClient, DownstreamRetryExecutor retryExecutor) {
        this.restClient = restClient;
        this.retryExecutor = retryExecutor;
    }

    public void update(
            SellerInventoryChanged event, CanonicalProductContext productContext) {
        AvailabilityUpdate update = new AvailabilityUpdate(
                event.availableQuantity(), event.sourceVersion(), productContext);
        retryExecutor.execute(() -> {
            restClient.put()
                    .uri("/v1/availability/{sellerId}/{productId}/{sizeId}?countryCode={countryCode}",
                            event.sellerId(), event.productId(), event.sizeId(), event.countryCode())
                    .body(update)
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
    }
}
