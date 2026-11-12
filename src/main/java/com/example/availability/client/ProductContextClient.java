package com.example.availability.client;

import com.example.availability.model.ProductContext;
import com.example.availability.model.ProductContextSnapshotEntry;
import com.example.availability.service.ProductContextNotAvailableException;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class ProductContextClient {

    private final RestClient restClient;
    private final DownstreamRetryExecutor retryExecutor;

    public ProductContextClient(RestClient restClient, DownstreamRetryExecutor retryExecutor) {
        this.restClient = restClient;
        this.retryExecutor = retryExecutor;
    }

    public ProductContext get(String productId, String sizeId, String countryCode) {
        return retryExecutor.execute(() -> getOnce(productId, sizeId, countryCode));
    }

    public List<ProductContextSnapshotEntry> getSnapshot(String countryCode) {
        return retryExecutor.execute(() -> {
            List<ProductContextSnapshotEntry> snapshot = restClient.get()
                    .uri("/v1/product-contexts/snapshot?countryCode={countryCode}", countryCode)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (snapshot == null) {
                throw new RestClientException(
                        "Product Context snapshot API returned an empty response.");
            }
            return snapshot;
        });
    }

    private ProductContext getOnce(String productId, String sizeId, String countryCode) {
        try {
            ProductContext context = restClient.get()
                    .uri("/v1/products/{productId}/sizes/{sizeId}?countryCode={countryCode}",
                            productId, sizeId, countryCode)
                    .retrieve()
                    .body(ProductContext.class);
            if (context == null) {
                throw new RestClientException("Product Context API returned an empty response.");
            }
            return context;
        } catch (HttpClientErrorException.NotFound exception) {
            throw new ProductContextNotAvailableException(
                    "No product context exists for the requested product, size, and country.");
        }
    }
}
