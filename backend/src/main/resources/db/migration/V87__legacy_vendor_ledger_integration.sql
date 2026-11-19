-- Legacy TTL/TSF ledger integration. Credentials are encrypted write-only; no token or password is
-- persisted in clear text. Registration vendor payloads can contain provisioning material and are
-- therefore encrypted at rest with the shared payload cipher.

CREATE TABLE legacy_source_config (
    driver_key VARCHAR(64) PRIMARY KEY,
    origin VARCHAR(500) NOT NULL,
    username_cipher TEXT NOT NULL,
    username_nonce VARCHAR(64) NOT NULL,
    password_cipher TEXT NOT NULL,
    password_nonce VARCHAR(64) NOT NULL,
    account_fingerprint VARCHAR(64) NOT NULL,
    allow_insecure_http BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(64) NOT NULL REFERENCES app_users(id)
);

ALTER TABLE device_registrations ADD COLUMN vendor_payload_cipher TEXT;
