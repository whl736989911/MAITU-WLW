-- Keep the old product name per protocol version, not per shared manufacturer/model.
ALTER TABLE product_versions ADD COLUMN legacy_product_name VARCHAR(200);

CREATE TABLE legacy_excel_product_platforms (
    product_version_id VARCHAR(64) NOT NULL REFERENCES product_versions(id) ON DELETE CASCADE,
    platform_name VARCHAR(200) NOT NULL,
    PRIMARY KEY (product_version_id, platform_name)
);
