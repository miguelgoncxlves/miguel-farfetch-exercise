package com.example.availability.version;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcStockVersionStore implements StockVersionStore {

    private static final String UPDATE_SQL = """
            UPDATE stock_processing_state
            SET source_version = ?, phase = ?, state_json = ?
            WHERE seller_id = ? AND product_id = ? AND size_id = ? AND country_code = ?
            """;

    private static final String INSERT_SQL = """
            INSERT INTO stock_processing_state
                (seller_id, product_id, size_id, country_code, source_version, phase, state_json)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcStockVersionStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<StockVersionState> state(StockKey key) {
        List<StockVersionState> states = jdbcTemplate.query(
                """
                SELECT state_json FROM stock_processing_state
                WHERE seller_id = ? AND product_id = ? AND size_id = ? AND country_code = ?
                """,
                (resultSet, rowNumber) -> deserialize(resultSet),
                key.sellerId(),
                key.productId(),
                key.sizeId(),
                key.countryCode());
        return states.stream().findFirst();
    }

    @Override
    public void save(StockKey key, StockVersionState state) {
        String stateJson = serialize(state);
        Object[] updateArguments = {
            state.sourceVersion(),
            state.phase().name(),
            stateJson,
            key.sellerId(),
            key.productId(),
            key.sizeId(),
            key.countryCode()
        };
        if (jdbcTemplate.update(UPDATE_SQL, updateArguments) > 0) {
            return;
        }

        try {
            jdbcTemplate.update(
                    INSERT_SQL,
                    key.sellerId(),
                    key.productId(),
                    key.sizeId(),
                    key.countryCode(),
                    state.sourceVersion(),
                    state.phase().name(),
                    stateJson);
        } catch (DuplicateKeyException exception) {
            jdbcTemplate.update(UPDATE_SQL, updateArguments);
        }
    }

    @Override
    public List<StockKey> pendingKeys() {
        return jdbcTemplate.query(
                """
                SELECT seller_id, product_id, size_id, country_code
                FROM stock_processing_state
                WHERE phase IN (?, ?)
                """,
                (resultSet, rowNumber) -> new StockKey(
                        resultSet.getString("seller_id"),
                        resultSet.getString("product_id"),
                        resultSet.getString("size_id"),
                        resultSet.getString("country_code")),
                StockProcessingPhase.AVAILABILITY_PENDING.name(),
                StockProcessingPhase.PUBLICATION_PENDING.name());
    }

    private String serialize(StockVersionState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize stock processing state.", exception);
        }
    }

    private StockVersionState deserialize(ResultSet resultSet) throws SQLException {
        try {
            return objectMapper.readValue(resultSet.getString("state_json"), StockVersionState.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not deserialize stock processing state.", exception);
        }
    }
}
