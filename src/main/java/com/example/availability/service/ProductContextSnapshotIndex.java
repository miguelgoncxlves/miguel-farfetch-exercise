package com.example.availability.service;

import com.example.availability.api.SellerInventoryChanged;
import com.example.availability.model.ProductContext;
import com.example.availability.model.ProductContextSnapshotEntry;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.web.client.RestClientException;

final class ProductContextSnapshotIndex {

    private final Map<ProductContextKey, ProductContext> contexts;
    private final boolean available;

    private ProductContextSnapshotIndex(
            Map<ProductContextKey, ProductContext> contexts, boolean available) {
        this.contexts = contexts;
        this.available = available;
    }

    static ProductContextSnapshotIndex create(
            String countryCode,
            Iterable<ProductContextSnapshotEntry> snapshot,
            Set<ProductContextKey> requestedKeys) {
        Map<ProductContextKey, ProductContext> contexts = new HashMap<>();
        for (ProductContextSnapshotEntry entry : snapshot) {
            if (entry == null
                    || isBlank(entry.productId())
                    || isBlank(entry.sizeId())
                    || isBlank(entry.countryCode())) {
                throw new RestClientException(
                        "Product Context snapshot contains an entry without a complete key.");
            }

            ProductContextKey key =
                    new ProductContextKey(entry.productId(), entry.sizeId(), entry.countryCode());
            if (!countryCode.equals(entry.countryCode()) || !requestedKeys.contains(key)) {
                continue;
            }
            if (contexts.putIfAbsent(key, entry.context()) != null) {
                throw new RestClientException(
                        "Product Context snapshot contains duplicate entries for a requested key.");
            }
        }
        return new ProductContextSnapshotIndex(Map.copyOf(contexts), true);
    }

    static ProductContextSnapshotIndex unavailable() {
        return new ProductContextSnapshotIndex(Map.of(), false);
    }

    ProductContext resolve(SellerInventoryChanged event) {
        if (!available) {
            throw new RestClientException("Product Context snapshot is unavailable.");
        }
        ProductContext context = contexts.get(keyFor(event));
        if (context == null) {
            throw new ProductContextNotAvailableException(
                    "No product context exists for the requested product, size, and country.");
        }
        return context;
    }

    static ProductContextKey keyFor(SellerInventoryChanged event) {
        return new ProductContextKey(event.productId(), event.sizeId(), event.countryCode());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

record ProductContextKey(String productId, String sizeId, String countryCode) {
}
