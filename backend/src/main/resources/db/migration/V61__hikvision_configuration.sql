CREATE TABLE hikvision_config (
 id INTEGER PRIMARY KEY CHECK(id=1),
 endpoint VARCHAR(500) NOT NULL,
 app_key VARCHAR(200) NOT NULL,
 app_key_masked VARCHAR(200) NOT NULL,
 secret_cipher TEXT NOT NULL,
 secret_nonce VARCHAR(64) NOT NULL,
 account_fingerprint VARCHAR(64) NOT NULL,
 enabled BOOLEAN NOT NULL,
 poll_interval_seconds INTEGER NOT NULL CHECK(poll_interval_seconds BETWEEN 10 AND 3600),
 model_scope VARCHAR(500) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_by VARCHAR(64) NOT NULL
);
