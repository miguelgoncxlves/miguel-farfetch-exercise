package com.example.availability.version;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.availability.api.SellerInventoryChanged;
import com.example.availability.model.CanonicalProductContext;
import com.example.availability.model.ProductContext;
import com.example.availability.model.SizeAvailabilityUpdated;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcStockVersionStoreTest {

    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:stock-state-test;DB_CLOSE_DELAY=-1");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE stock_processing_state (
                    seller_id VARCHAR(255) NOT NULL,
                    product_id VARCHAR(255) NOT NULL,
                    size_id VARCHAR(255) NOT NULL,
                    country_code VARCHAR(2) NOT NULL,
                    source_version BIGINT NOT NULL,
                    phase VARCHAR(32) NOT NULL,
                    state_json CLOB NOT NULL,
                    PRIMARY KEY (seller_id, product_id, size_id, country_code)
                )
                """);
        objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    }

    @Test
    void persistsPendingOperationAcrossStoreRecreationAndClearsCompletedOutbox() {
        StockKey key = new StockKey("boutique-42", "style-123", "size-38", "PT");
        SellerInventoryChanged input = new SellerInventoryChanged(
                "message-7",
                "SellerInventoryChanged",
                1,
                Instant.parse("2026-10-20T08:00:00Z"),
                key.sellerId(),
                key.productId(),
                key.sizeId(),
                key.countryCode(),
                3,
                7L);
        CanonicalProductContext context = new CanonicalProductContext(
                "Linen Shirt 123",
                new ProductContext.Brand("brand-42", "Atelier Example"),
                "EU 38",
                "EU");
        SizeAvailabilityUpdated output = new SizeAvailabilityUpdated(
                "stable-output-id",
                "SizeAvailabilityUpdated",
                1,
                Instant.parse("2026-10-20T08:01:00Z"),
                key.sellerId(),
                key.productId(),
                key.sizeId(),
                key.countryCode(),
                3,
                7L,
                context);

        StockVersionStore firstStore = new JdbcStockVersionStore(jdbcTemplate, objectMapper);
        StockVersionState pendingState =
                StockVersionState.availabilityPending(input, context, output);
        firstStore.save(key, pendingState);

        StockVersionStore recreatedStore = new JdbcStockVersionStore(jdbcTemplate, objectMapper);
        assertThat(recreatedStore.state(key)).contains(pendingState);
        assertThat(recreatedStore.pendingKeys()).containsExactly(key);

        StockVersionState publicationPending = pendingState.publicationPending();
        recreatedStore.save(key, publicationPending);
        assertThat(firstStore.state(key)).contains(publicationPending);

        recreatedStore.save(key, publicationPending.complete());
        assertThat(firstStore.state(key)).contains(publicationPending.complete());
        assertThat(recreatedStore.pendingKeys()).isEmpty();
    }
}
