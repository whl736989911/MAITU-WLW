ALTER TABLE notification_rules RENAME COLUMN mode TO legacy_mode;
ALTER TABLE notification_rules RENAME COLUMN sms_template_id TO legacy_sms_template_id;
ALTER TABLE notification_rules RENAME COLUMN voice_template_id TO legacy_voice_template_id;
ALTER TABLE notification_rules RENAME COLUMN max_attempts TO legacy_max_attempts;
ALTER TABLE notification_rules RENAME COLUMN retry_delay_seconds TO legacy_retry_delay_seconds;

ALTER TABLE notification_rules ALTER COLUMN legacy_mode DROP NOT NULL;
ALTER TABLE notification_rules ALTER COLUMN legacy_mode DROP DEFAULT;
ALTER TABLE notification_rules ALTER COLUMN legacy_sms_template_id DROP NOT NULL;
ALTER TABLE notification_rules ALTER COLUMN legacy_sms_template_id DROP DEFAULT;
ALTER TABLE notification_rules ALTER COLUMN legacy_voice_template_id DROP NOT NULL;
ALTER TABLE notification_rules ALTER COLUMN legacy_voice_template_id DROP DEFAULT;
ALTER TABLE notification_rules ALTER COLUMN legacy_max_attempts DROP NOT NULL;
ALTER TABLE notification_rules ALTER COLUMN legacy_max_attempts DROP DEFAULT;
ALTER TABLE notification_rules ALTER COLUMN legacy_retry_delay_seconds DROP NOT NULL;
ALTER TABLE notification_rules ALTER COLUMN legacy_retry_delay_seconds DROP DEFAULT;
