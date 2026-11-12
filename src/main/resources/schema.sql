CREATE TABLE IF NOT EXISTS stock_processing_state (
    seller_id VARCHAR(255) NOT NULL,
    product_id VARCHAR(255) NOT NULL,
    size_id VARCHAR(255) NOT NULL,
    country_code VARCHAR(2) NOT NULL,
    source_version BIGINT NOT NULL,
    phase VARCHAR(32) NOT NULL,
    state_json CLOB NOT NULL,
    PRIMARY KEY (seller_id, product_id, size_id, country_code)
);
