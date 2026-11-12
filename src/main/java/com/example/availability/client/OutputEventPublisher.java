package com.example.availability.client;

import com.example.availability.model.SizeAvailabilityUpdated;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OutputEventPublisher {

    private final RestClient restClient;
    private final DownstreamRetryExecutor retryExecutor;

    public OutputEventPublisher(RestClient restClient, DownstreamRetryExecutor retryExecutor) {
        this.restClient = restClient;
        this.retryExecutor = retryExecutor;
    }

    public void publish(SizeAvailabilityUpdated event) {
        retryExecutor.execute(() -> {
            restClient.post()
                    .uri("/mock-broker/v1/events")
                    .body(event)
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
    }
}
