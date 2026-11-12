package com.example.availability.client;

import com.example.availability.model.SizeAvailabilityUpdated;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OutputEventPublisher {

    private final RestClient restClient;

    public OutputEventPublisher(RestClient restClient) {
        this.restClient = restClient;
    }

    public void publish(SizeAvailabilityUpdated event) {
        restClient.post()
                .uri("/mock-broker/v1/events")
                .body(event)
                .retrieve()
                .toBodilessEntity();
    }
}
