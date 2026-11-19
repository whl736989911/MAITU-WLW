CREATE TABLE notification_provider_config (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    sms_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    voice_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    sms_sign_name VARCHAR(200),
    sms_template_code VARCHAR(100),
    tts_code VARCHAR(100),
    tts_called_show_number VARCHAR(50),
    sms_endpoint VARCHAR(255) NOT NULL DEFAULT 'https://dysmsapi.aliyuncs.com/',
    voice_endpoint VARCHAR(255) NOT NULL DEFAULT 'https://dyvmsapi.aliyuncs.com/',
    access_key_id VARCHAR(200),
    access_key_secret_cipher TEXT,
    access_key_secret_nonce TEXT,
    access_key_secret_masked VARCHAR(64),
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE,
    updated_by VARCHAR(64)
);
INSERT INTO notification_provider_config (id, sms_enabled, voice_enabled, sms_endpoint, voice_endpoint)
VALUES (1, FALSE, FALSE, 'https://dysmsapi.aliyuncs.com/', 'https://dyvmsapi.aliyuncs.com/');
