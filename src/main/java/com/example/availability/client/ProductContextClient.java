package com.example.availability.client;

import com.example.availability.model.ProductContext;
import com.example.availability.service.ProductContextNotAvailableException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class ProductContextClient {

    private final RestClient restClient;

    public ProductContextClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public ProductContext get(String productId, String sizeId, String countryCode) {
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
