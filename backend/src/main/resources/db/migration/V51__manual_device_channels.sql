CREATE TABLE device_channels (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    channel_key VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL,
    channel_type VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_device_channels_tenant_device_key UNIQUE(tenant_id,device_id,channel_key),
    CONSTRAINT uq_device_channels_id_tenant_device UNIQUE(id,tenant_id,device_id),
    CONSTRAINT fk_device_channels_device_tenant FOREIGN KEY(device_id,tenant_id) REFERENCES devices(id,tenant_id),
    CONSTRAINT ck_device_channels_id CHECK(CHAR_LENGTH(id) BETWEEN 1 AND 64),
    CONSTRAINT ck_device_channels_channel_key CHECK(CHAR_LENGTH(channel_key) BETWEEN 1 AND 64),
    CONSTRAINT ck_device_channels_name CHECK(CHAR_LENGTH(name) BETWEEN 1 AND 120),
    CONSTRAINT ck_device_channels_type CHECK(CHAR_LENGTH(channel_type) BETWEEN 1 AND 64)
);
CREATE INDEX idx_device_channels_tenant_device ON device_channels(tenant_id,device_id,channel_key,id);
