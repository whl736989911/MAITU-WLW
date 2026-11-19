ALTER TABLE notification_provider_config ADD COLUMN test_mode_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE notification_provider_config ADD COLUMN test_phone_cipher TEXT;
ALTER TABLE notification_provider_config ADD COLUMN test_phone_nonce TEXT;
ALTER TABLE notification_provider_config ADD COLUMN test_phone_masked VARCHAR(64);
