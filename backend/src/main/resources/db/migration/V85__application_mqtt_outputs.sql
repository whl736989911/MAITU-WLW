-- MQTT 3.1.1 over TLS output for the existing open-application delivery pipeline.
-- Additive only: every existing output stays HTTPS with its current HTTP auth scheme, nothing is enabled,
-- no legacy MQTT endpoint or credential is imported, and no permission is granted here.
--
-- The outbox needs no new protocol column: every captured row already freezes frozen_endpoint_origin plus
-- frozen_endpoint_digest and frozen_auth_scheme. An MQTT target origin is mqtts://<host>:8883 and its frozen
-- digest covers protocol + normalized URL + topic, so a protocol/target/topic change invalidates the claim
-- before any I/O. HTTPS digests remain the historical SHA-256 of the URL alone.

ALTER TABLE application_outputs ADD COLUMN protocol VARCHAR(24) NOT NULL DEFAULT 'HTTPS';
ALTER TABLE application_outputs ADD CONSTRAINT ck_application_outputs_protocol
    CHECK (protocol IN ('HTTPS','MQTT_TLS_3_1_1'));

-- Separate MQTT auth scheme: HTTP auth_scheme/credential columns stay HTTP-only, and MQTT uses username+password.
ALTER TABLE application_outputs ADD COLUMN mqtt_auth_scheme VARCHAR(24) NOT NULL DEFAULT 'NONE';
ALTER TABLE application_outputs ADD CONSTRAINT ck_application_outputs_mqtt_auth
    CHECK (mqtt_auth_scheme IN ('NONE','USERNAME_PASSWORD'));

-- Topic, username and password are write-only configuration encrypted with the existing application output key.
ALTER TABLE application_outputs ADD COLUMN mqtt_topic_cipher TEXT;
ALTER TABLE application_outputs ADD COLUMN mqtt_topic_nonce VARCHAR(64);
ALTER TABLE application_outputs ADD COLUMN mqtt_topic_masked VARCHAR(255);
ALTER TABLE application_outputs ADD COLUMN mqtt_topic_digest VARCHAR(64);
ALTER TABLE application_outputs ADD COLUMN mqtt_username_cipher TEXT;
ALTER TABLE application_outputs ADD COLUMN mqtt_username_nonce VARCHAR(64);
ALTER TABLE application_outputs ADD COLUMN mqtt_username_masked VARCHAR(64);
ALTER TABLE application_outputs ADD COLUMN mqtt_username_digest VARCHAR(64);
ALTER TABLE application_outputs ADD COLUMN mqtt_password_cipher TEXT;
ALTER TABLE application_outputs ADD COLUMN mqtt_password_nonce VARCHAR(64);
ALTER TABLE application_outputs ADD COLUMN mqtt_password_masked VARCHAR(64);
ALTER TABLE application_outputs ADD COLUMN mqtt_password_digest VARCHAR(64);

-- HTTP and MQTT credentials never coexist on one output. An MQTT draft may omit destination/auth material until
-- an operator supplies it, but it can never carry an HTTP header/bearer/basic credential.
ALTER TABLE application_outputs ADD CONSTRAINT ck_application_outputs_transport CHECK (
    (protocol = 'HTTPS' AND mqtt_auth_scheme = 'NONE'
        AND mqtt_topic_cipher IS NULL AND mqtt_topic_nonce IS NULL AND mqtt_topic_masked IS NULL
        AND mqtt_topic_digest IS NULL AND mqtt_username_cipher IS NULL AND mqtt_username_nonce IS NULL
        AND mqtt_username_masked IS NULL AND mqtt_username_digest IS NULL AND mqtt_password_cipher IS NULL
        AND mqtt_password_nonce IS NULL AND mqtt_password_masked IS NULL AND mqtt_password_digest IS NULL)
    OR (protocol = 'MQTT_TLS_3_1_1' AND mqtt_auth_scheme = 'USERNAME_PASSWORD' AND auth_scheme = 'NONE'
        AND auth_header_name IS NULL AND credential_cipher IS NULL AND credential_nonce IS NULL
        AND credential_masked IS NULL AND credential_digest IS NULL)
);

-- Enabling an MQTT output requires a complete TLS target plus topic and username/password material.
ALTER TABLE application_outputs ADD CONSTRAINT ck_application_outputs_mqtt_enabled CHECK (
    protocol = 'HTTPS' OR enabled = FALSE
        OR (endpoint_url_cipher IS NOT NULL AND endpoint_digest IS NOT NULL
            AND mqtt_topic_cipher IS NOT NULL AND mqtt_topic_nonce IS NOT NULL
            AND mqtt_username_cipher IS NOT NULL AND mqtt_username_nonce IS NOT NULL
            AND mqtt_password_cipher IS NOT NULL AND mqtt_password_nonce IS NOT NULL)
);
