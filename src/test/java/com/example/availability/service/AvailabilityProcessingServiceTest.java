package com.example.availability.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.availability.api.SellerInventoryChanged;
import com.example.availability.client.AvailabilityClient;
import com.example.availability.client.OutputEventPublisher;
import com.example.availability.client.ProductContextClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

class AvailabilityProcessingServiceTest {

    private static final String BASE_URL = "http://wiremock.test";

    private MockRestServiceServer server;
    private AvailabilityProcessingService processingService;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        processingService = new AvailabilityProcessingService(
                new ProductContextClient(restClient),
                new AvailabilityClient(restClient),
                new OutputEventPublisher(restClient),
                Clock.fixed(Instant.parse("2026-10-20T08:01:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void enrichesWritesAvailabilityAndPublishesOutputEventInOrder() {
        server.expect(requestTo(
                        BASE_URL + "/v1/products/style-123/sizes/size-38?countryCode=PT"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(productContext("ACTIVE", true), MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        BASE_URL + "/v1/availability/boutique-42/style-123/size-38?countryCode=PT"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.availableQuantity").value(3))
                .andExpect(jsonPath("$.sourceVersion").value(7))
                .andExpect(jsonPath("$.productContext.productName").value("Linen Shirt 123"))
                .andRespond(withSuccess());
        server.expect(requestTo(BASE_URL + "/mock-broker/v1/events"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.eventType").value("SizeAvailabilityUpdated"))
                .andExpect(jsonPath("$.occurredAt").value("2026-10-20T08:01:00Z"))
                .andExpect(jsonPath("$.sellerId").value("boutique-42"))
                .andExpect(jsonPath("$.availableQuantity").value(3))
                .andExpect(jsonPath("$.sourceVersion").value(7))
                .andExpect(jsonPath("$.productContext.brand.name").value("Atelier Example"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        processingService.process(event());
    }

    @Test
    void doesNotWriteOrPublishWhenContextIsInactive() {
        server.expect(requestTo(
                        BASE_URL + "/v1/products/style-123/sizes/size-38?countryCode=PT"))
                .andRespond(withSuccess(productContext("INACTIVE", true), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> processingService.process(event()))
                .isInstanceOf(ProductContextNotAvailableException.class);
    }

    @Test
    void doesNotWriteOrPublishWhenCountryIsDisabled() {
        server.expect(requestTo(
                        BASE_URL + "/v1/products/style-123/sizes/size-38?countryCode=PT"))
                .andRespond(withSuccess(productContext("ACTIVE", false), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> processingService.process(event()))
                .isInstanceOf(ProductContextNotAvailableException.class);
    }

    @Test
    void treatsUnknownContextAsNotApplicable() {
        server.expect(requestTo(
                        BASE_URL + "/v1/products/style-123/sizes/size-38?countryCode=PT"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> processingService.process(event()))
                .isInstanceOf(ProductContextNotAvailableException.class)
                .hasMessageContaining("No product context");
    }

    @Test
    void surfacesDependencyFailureWithoutAttemptingDownstreamWrites() {
        server.expect(requestTo(
                        BASE_URL + "/v1/products/style-123/sizes/size-38?countryCode=PT"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> processingService.process(event()))
                .isInstanceOf(HttpServerErrorException.ServiceUnavailable.class);
    }

    private SellerInventoryChanged event() {
        return new SellerInventoryChanged(
                "msg-0001",
                "SellerInventoryChanged",
                1,
                Instant.parse("2026-10-20T08:00:00Z"),
                "boutique-42",
                "style-123",
                "size-38",
                "PT",
                3,
                7L);
    }

    private String productContext(String status, boolean countryEnabled) {
        return """
                {
                  "productId": "style-123",
                  "productName": "Linen Shirt 123",
                  "brand": {"id": "brand-42", "name": "Atelier Example"},
                  "productStatus": "%s",
                  "sizeId": "size-38",
                  "sizeLabel": "EU 38",
                  "sizeSystem": "EU",
                  "countryCode": "PT",
                  "countryEnabled": %s
                }
                """.formatted(status, countryEnabled);
    }
}
