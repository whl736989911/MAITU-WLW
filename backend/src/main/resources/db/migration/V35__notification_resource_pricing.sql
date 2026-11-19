CREATE TABLE notification_resource_pricing (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    sms_price_cents BIGINT CHECK (sms_price_cents IS NULL OR sms_price_cents >= 0),
    voice_price_cents BIGINT CHECK (voice_price_cents IS NULL OR voice_price_cents >= 0),
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE,
    updated_by VARCHAR(64)
);
INSERT INTO notification_resource_pricing (id, revision) VALUES (1, 0);
