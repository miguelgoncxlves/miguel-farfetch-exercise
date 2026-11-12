package com.example.availability.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.availability.api.SellerInventoryChanged;
import com.example.availability.client.AvailabilityClient;
import com.example.availability.client.DownstreamRetryExecutor;
import com.example.availability.client.OutputEventPublisher;
import com.example.availability.client.ProductContextClient;
import com.example.availability.model.ProductContext;
import com.example.availability.version.InMemoryStockVersionStore;
import com.example.availability.version.StockProcessingPhase;
import com.example.availability.version.StockKey;
import com.example.availability.version.StockKeyLockRegistry;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

class AvailabilityProcessingServiceTest {

    private static final String BASE_URL = "http://wiremock.test";

    private MockRestServiceServer server;
    private AvailabilityProcessingService processingService;
    private List<Duration> retryDelays;
    private InMemoryStockVersionStore versionStore;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        Clock clock = Clock.fixed(Instant.parse("2026-10-20T08:01:00Z"), ZoneOffset.UTC);
        retryDelays = new ArrayList<>();
        DownstreamRetryExecutor retryExecutor =
                new DownstreamRetryExecutor(clock, 3, Duration.ofMillis(100), retryDelays::add);
        versionStore = new InMemoryStockVersionStore();
        processingService = new AvailabilityProcessingService(
                new ProductContextClient(restClient, retryExecutor),
                new AvailabilityClient(restClient, retryExecutor),
                new OutputEventPublisher(restClient, retryExecutor),
                versionStore,
                new StockKeyLockRegistry(),
                clock);
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

        assertThat(processingService.process(event())).isEqualTo(ProcessingResult.processed());
    }

    @Test
    void processesUsingProvidedContextWithoutCallingTheSingleItemEndpoint() {
        ProductContext context = new ProductContext(
                "Linen Shirt 123",
                new ProductContext.Brand("brand-42", "Atelier Example"),
                "ACTIVE",
                "EU 38",
                "EU",
                true);
        server.expect(requestTo(
                        BASE_URL + "/v1/availability/boutique-42/style-123/size-38?countryCode=PT"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess());
        server.expect(requestTo(BASE_URL + "/mock-broker/v1/events"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.productContext.productName").value("Linen Shirt 123"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThat(processingService.process(event(), () -> context))
                .isEqualTo(ProcessingResult.processed());
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
        for (int attempt = 0; attempt < 3; attempt++) {
            server.expect(requestTo(
                            BASE_URL + "/v1/products/style-123/sizes/size-38?countryCode=PT"))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                            .header(HttpHeaders.RETRY_AFTER, "0"));
        }

        assertThatThrownBy(() -> processingService.process(event()))
                .isInstanceOf(HttpServerErrorException.ServiceUnavailable.class);
        assertThat(retryDelays).containsExactly(Duration.ZERO, Duration.ZERO);
    }

    @Test
    void retriesTransientFailuresAndHonorsRetryAfter() {
        server.expect(requestTo(
                        BASE_URL + "/v1/products/style-123/sizes/size-38?countryCode=PT"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .header(HttpHeaders.RETRY_AFTER, "Tue, 20 Oct 2026 08:01:02 GMT"));
        server.expect(requestTo(
                        BASE_URL + "/v1/products/style-123/sizes/size-38?countryCode=PT"))
                .andRespond(withSuccess(productContext("ACTIVE", true), MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        BASE_URL + "/v1/availability/boutique-42/style-123/size-38?countryCode=PT"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, "0"));
        server.expect(requestTo(
                        BASE_URL + "/v1/availability/boutique-42/style-123/size-38?countryCode=PT"))
                .andRespond(withSuccess());
        server.expect(requestTo(BASE_URL + "/mock-broker/v1/events"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .header(HttpHeaders.RETRY_AFTER, "0"));
        server.expect(requestTo(BASE_URL + "/mock-broker/v1/events"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThat(processingService.process(event())).isEqualTo(ProcessingResult.processed());
        assertThat(retryDelays)
                .containsExactly(Duration.ofSeconds(2), Duration.ZERO, Duration.ZERO);
    }

    @Test
    void usesExponentialBackoffWhenRetryAfterIsMissing() {
        for (int attempt = 0; attempt < 2; attempt++) {
            server.expect(requestTo(
                            BASE_URL + "/v1/products/style-123/sizes/size-38?countryCode=PT"))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }
        server.expect(requestTo(
                        BASE_URL + "/v1/products/style-123/sizes/size-38?countryCode=PT"))
                .andRespond(withSuccess(productContext("ACTIVE", true), MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        BASE_URL + "/v1/availability/boutique-42/style-123/size-38?countryCode=PT"))
                .andRespond(withSuccess());
        server.expect(requestTo(BASE_URL + "/mock-broker/v1/events"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThat(processingService.process(event())).isEqualTo(ProcessingResult.processed());
        assertThat(retryDelays)
                .containsExactly(Duration.ofMillis(100), Duration.ofMillis(200));
    }

    @Test
    void stopsAfterThreeAttemptsAndDoesNotAdvanceTheVersion() {
        for (int attempt = 0; attempt < 3; attempt++) {
            server.expect(requestTo(
                            BASE_URL + "/v1/products/style-123/sizes/size-38?countryCode=PT"))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                            .header(HttpHeaders.RETRY_AFTER, "0"));
        }

        assertThatThrownBy(() -> processingService.process(event()))
                .isInstanceOf(HttpServerErrorException.ServiceUnavailable.class);
        assertThat(retryDelays).containsExactly(Duration.ZERO, Duration.ZERO);
        assertThat(versionStore.state(
                new StockKey("boutique-42", "style-123", "size-38", "PT"))).isEmpty();
    }

    @Test
    void skipsStaleAndDuplicateVersionsForTheSameStockKey() {
        server.expect(requestTo(
                        BASE_URL + "/v1/products/style-123/sizes/size-38?countryCode=PT"))
                .andRespond(withSuccess(productContext("ACTIVE", true), MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        BASE_URL + "/v1/availability/boutique-42/style-123/size-38?countryCode=PT"))
                .andRespond(withSuccess());
        server.expect(requestTo(BASE_URL + "/mock-broker/v1/events"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThat(processingService.process(event(8L, "msg-0008", "boutique-42")))
                .isEqualTo(ProcessingResult.processed());
        assertThat(processingService.process(event(7L, "replay-old", "boutique-42"), () -> {
                    throw new AssertionError("A stale event must not resolve its Product Context.");
                }))
                .isEqualTo(ProcessingResult.skipped("stale_version"));
        assertThat(processingService.process(event(8L, "replay-new-id", "boutique-42"), () -> {
                    throw new AssertionError("A duplicate event must not resolve its Product Context.");
                }))
                .isEqualTo(ProcessingResult.skipped("duplicate_version"));
    }

    @Test
    void tracksVersionsIndependentlyForDifferentSellers() {
        for (String sellerId : List.of("boutique-42", "boutique-43")) {
            server.expect(requestTo(
                            BASE_URL + "/v1/products/style-123/sizes/size-38?countryCode=PT"))
                    .andRespond(withSuccess(productContext("ACTIVE", true), MediaType.APPLICATION_JSON));
            server.expect(requestTo(
                            BASE_URL + "/v1/availability/" + sellerId
                                    + "/style-123/size-38?countryCode=PT"))
                    .andRespond(withSuccess());
            server.expect(requestTo(BASE_URL + "/mock-broker/v1/events"))
                    .andRespond(withStatus(HttpStatus.ACCEPTED));
        }

        assertThat(processingService.process(event(8L, "seller-one", "boutique-42")))
                .isEqualTo(ProcessingResult.processed());
        assertThat(processingService.process(event(7L, "seller-two", "boutique-43")))
                .isEqualTo(ProcessingResult.processed());
    }

    @Test
    void retriesPendingOutputOnReplayWithoutRepeatingTheAvailabilityWrite() {
        server.expect(requestTo(
                        BASE_URL + "/v1/products/style-123/sizes/size-38?countryCode=PT"))
                .andRespond(withSuccess(productContext("ACTIVE", true), MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        BASE_URL + "/v1/availability/boutique-42/style-123/size-38?countryCode=PT"))
                .andRespond(withSuccess());
        for (int attempt = 0; attempt < 3; attempt++) {
            server.expect(requestTo(BASE_URL + "/mock-broker/v1/events"))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                            .header(HttpHeaders.RETRY_AFTER, "0"));
        }
        server.expect(requestTo(BASE_URL + "/mock-broker/v1/events"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThatThrownBy(() -> processingService.process(event()))
                .isInstanceOf(HttpServerErrorException.ServiceUnavailable.class);
        assertThat(versionStore.state(
                new StockKey("boutique-42", "style-123", "size-38", "PT")))
                .hasValueSatisfying(state -> {
                    assertThat(state.phase()).isEqualTo(StockProcessingPhase.PUBLICATION_PENDING);
                    assertThat(state.outputEvent()).isNotNull();
                });

        assertThat(processingService.process(event(7L, "replayed-message-id", "boutique-42")))
                .isEqualTo(ProcessingResult.processed());
        assertThat(versionStore.state(
                new StockKey("boutique-42", "style-123", "size-38", "PT")))
                .hasValueSatisfying(state -> {
                    assertThat(state.phase()).isEqualTo(StockProcessingPhase.COMPLETE);
                    assertThat(state.outputEvent()).isNull();
                });
    }

    @Test
    void recoversPendingOutputWithoutRepeatingAvailabilityWrite() {
        server.expect(requestTo(
                        BASE_URL + "/v1/products/style-123/sizes/size-38?countryCode=PT"))
                .andRespond(withSuccess(productContext("ACTIVE", true), MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        BASE_URL + "/v1/availability/boutique-42/style-123/size-38?countryCode=PT"))
                .andRespond(withSuccess());
        for (int attempt = 0; attempt < 3; attempt++) {
            server.expect(requestTo(BASE_URL + "/mock-broker/v1/events"))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                            .header(HttpHeaders.RETRY_AFTER, "0"));
        }
        server.expect(requestTo(BASE_URL + "/mock-broker/v1/events"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThatThrownBy(() -> processingService.process(event()))
                .isInstanceOf(HttpServerErrorException.ServiceUnavailable.class);

        processingService.recoverPendingOperations();

        assertThat(versionStore.state(
                new StockKey("boutique-42", "style-123", "size-38", "PT")))
                .hasValueSatisfying(state -> {
                    assertThat(state.phase()).isEqualTo(StockProcessingPhase.COMPLETE);
                    assertThat(state.outputEvent()).isNull();
                });
    }

    @Test
    void recoversAvailabilityPendingOperationUsingPersistedInputAndContext() {
        server.expect(requestTo(
                        BASE_URL + "/v1/products/style-123/sizes/size-38?countryCode=PT"))
                .andRespond(withSuccess(productContext("ACTIVE", true), MediaType.APPLICATION_JSON));
        for (int attempt = 0; attempt < 3; attempt++) {
            server.expect(requestTo(
                            BASE_URL + "/v1/availability/boutique-42/style-123/size-38?countryCode=PT"))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                            .header(HttpHeaders.RETRY_AFTER, "0"));
        }
        server.expect(requestTo(
                        BASE_URL + "/v1/availability/boutique-42/style-123/size-38?countryCode=PT"))
                .andRespond(withSuccess());
        server.expect(requestTo(BASE_URL + "/mock-broker/v1/events"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThatThrownBy(() -> processingService.process(event()))
                .isInstanceOf(HttpServerErrorException.ServiceUnavailable.class);
        assertThat(versionStore.state(
                new StockKey("boutique-42", "style-123", "size-38", "PT")))
                .hasValueSatisfying(state ->
                        assertThat(state.phase()).isEqualTo(StockProcessingPhase.AVAILABILITY_PENDING));

        processingService.recoverPendingOperations();

        assertThat(versionStore.state(
                new StockKey("boutique-42", "style-123", "size-38", "PT")))
                .hasValueSatisfying(state ->
                        assertThat(state.phase()).isEqualTo(StockProcessingPhase.COMPLETE));
    }

    @Test
    void doesNotAutomaticallyRetryPermanentPublicationFailureButAllowsReplay() {
        server.expect(requestTo(
                        BASE_URL + "/v1/products/style-123/sizes/size-38?countryCode=PT"))
                .andRespond(withSuccess(productContext("ACTIVE", true), MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        BASE_URL + "/v1/availability/boutique-42/style-123/size-38?countryCode=PT"))
                .andRespond(withSuccess());
        server.expect(requestTo(BASE_URL + "/mock-broker/v1/events"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        server.expect(requestTo(BASE_URL + "/mock-broker/v1/events"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThatThrownBy(() -> processingService.process(event()))
                .isInstanceOf(HttpClientErrorException.BadRequest.class);
        assertThat(versionStore.state(
                new StockKey("boutique-42", "style-123", "size-38", "PT")))
                .hasValueSatisfying(state ->
                        assertThat(state.phase()).isEqualTo(StockProcessingPhase.PUBLICATION_FAILED));
        assertThat(versionStore.pendingKeys()).isEmpty();

        assertThat(processingService.process(event(7L, "replay-after-fix", "boutique-42")))
                .isEqualTo(ProcessingResult.processed());
    }

    private SellerInventoryChanged event() {
        return event(7L, "msg-0001", "boutique-42");
    }

    private SellerInventoryChanged event(long sourceVersion, String eventId, String sellerId) {
        return new SellerInventoryChanged(
                eventId,
                "SellerInventoryChanged",
                1,
                Instant.parse("2026-10-20T08:00:00Z"),
                sellerId,
                "style-123",
                "size-38",
                "PT",
                3,
                sourceVersion);
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
