UPDATE notification_resource_pricing
SET sms_price_cents = COALESCE(sms_price_cents, 10),
    voice_price_cents = COALESCE(voice_price_cents, 25),
    revision = revision + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 1;
