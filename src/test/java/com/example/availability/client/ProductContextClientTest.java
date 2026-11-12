package com.example.availability.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.availability.model.ProductContextSnapshotEntry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ProductContextClientTest {

    private static final String BASE_URL = "http://wiremock.test";

    private MockRestServiceServer server;
    private ProductContextClient productContextClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        DownstreamRetryExecutor retryExecutor = new DownstreamRetryExecutor(
                Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneOffset.UTC),
                3,
                Duration.ofMillis(100),
                ignored -> {});
        productContextClient = new ProductContextClient(builder.build(), retryExecutor);
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void fetchesSnapshotByCountryAndMapsOnlyFieldsUsedByAvailability() {
        server.expect(requestTo(BASE_URL + "/v1/product-contexts/snapshot?countryCode=PT"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {
                            "productId": "style-123",
                            "productName": "Linen Shirt 123",
                            "brand": {"id": "brand-42", "name": "Atelier Example"},
                            "category": {"id": "cat-shirts", "name": "Shirts"},
                            "productStatus": "ACTIVE",
                            "sizeId": "size-38",
                            "sizeLabel": "EU 38",
                            "sizeSystem": "EU",
                            "countryCode": "PT",
                            "countryEnabled": true
                          }
                        ]
                        """, MediaType.APPLICATION_JSON));

        List<ProductContextSnapshotEntry> snapshot = productContextClient.getSnapshot("PT");

        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0).productId()).isEqualTo("style-123");
        assertThat(snapshot.get(0).sizeId()).isEqualTo("size-38");
        assertThat(snapshot.get(0).countryCode()).isEqualTo("PT");
        assertThat(snapshot.get(0).context().productName()).isEqualTo("Linen Shirt 123");
        assertThat(snapshot.get(0).context().isActiveAndCountryEnabled()).isTrue();
    }
}
