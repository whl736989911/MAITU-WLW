ALTER TABLE legacy_ingest_messages DROP CONSTRAINT ck_legacy_ingress_real_source;
ALTER TABLE legacy_ingest_messages ADD CONSTRAINT ck_legacy_ingress_real_source CHECK (ingress IN ('MQTT_IOT_UPLINK','MQTT_GATEWAY_UPLINK','HTTP_NOTIFY','HTTP_FORM_TTL','LORAWAN_NS','HIKVISION_POLL','HIKVISION_PUSH'));
CREATE TABLE hikvision_callback_config (
 id INTEGER PRIMARY KEY CHECK(id=1),
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 public_origin VARCHAR(500),
 credential_cipher VARCHAR(512),
 credential_nonce VARCHAR(128),
 account_fingerprint VARCHAR(64),
 revision BIGINT NOT NULL DEFAULT 1,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_by VARCHAR(64) NOT NULL
);
CREATE TABLE hikvision_callback_receipts (
 id VARCHAR(64) PRIMARY KEY,
 account_fingerprint VARCHAR(64) NOT NULL,
 model_scope VARCHAR(32) NOT NULL,
 payload_sha256 VARCHAR(64) NOT NULL,
 payload_bytes INTEGER NOT NULL,
 payload_json TEXT NOT NULL,
 received_at TIMESTAMP WITH TIME ZONE NOT NULL,
 last_received_at TIMESTAMP WITH TIME ZONE NOT NULL,
 duplicate_count BIGINT NOT NULL DEFAULT 0,
 state VARCHAR(16) NOT NULL CHECK(state IN ('PENDING','PROCESSED','PARTIAL','REJECTED')),
 message_types_json TEXT NOT NULL,
 submitted_records INTEGER NOT NULL DEFAULT 0,
 unsupported_messages INTEGER NOT NULL DEFAULT 0,
 error_code VARCHAR(64),
 processed_at TIMESTAMP WITH TIME ZONE,
 UNIQUE(account_fingerprint,payload_sha256)
);
CREATE INDEX idx_hikvision_callback_pending ON hikvision_callback_receipts(state,received_at,id);
ALTER TABLE hikvision_raw_records ADD COLUMN ingress VARCHAR(32) NOT NULL DEFAULT 'HIKVISION_POLL';
