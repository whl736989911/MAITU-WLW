ALTER TABLE notification_rule_receivers RENAME TO notification_rule_receivers_legacy;
CREATE TABLE notification_rule_receivers (
 tenant_id VARCHAR(64) NOT NULL, rule_id VARCHAR(64) NOT NULL, contact_id VARCHAR(64) NOT NULL, level INTEGER NOT NULL CHECK(level BETWEEN 1 AND 5),
 channel VARCHAR(5) NOT NULL CHECK(channel IN ('SMS','VOICE')),
 PRIMARY KEY(rule_id,contact_id,channel),
 FOREIGN KEY(tenant_id,rule_id) REFERENCES notification_rules(tenant_id,id),
 FOREIGN KEY(tenant_id,contact_id) REFERENCES notification_contacts(tenant_id,id)
);
INSERT INTO notification_rule_receivers(tenant_id,rule_id,contact_id,level,channel)
SELECT tenant_id,rule_id,contact_id,level,'SMS' FROM notification_rule_receivers_legacy;
INSERT INTO notification_rule_receivers(tenant_id,rule_id,contact_id,level,channel)
SELECT tenant_id,rule_id,contact_id,level,'VOICE' FROM notification_rule_receivers_legacy;
DROP TABLE notification_rule_receivers_legacy;
ALTER TABLE notification_rules ADD COLUMN sms_escalation_interval_seconds INTEGER CHECK(sms_escalation_interval_seconds BETWEEN 0 AND 604800);
ALTER TABLE notification_rules ADD COLUMN voice_escalation_interval_seconds INTEGER CHECK(voice_escalation_interval_seconds BETWEEN 0 AND 604800);
UPDATE notification_rules SET sms_escalation_interval_seconds=COALESCE(escalation_interval_seconds,0),voice_escalation_interval_seconds=COALESCE(escalation_interval_seconds,0);
CREATE INDEX idx_notification_receivers_channel ON notification_rule_receivers(rule_id,channel,level,contact_id);
CREATE TABLE notification_channel_policies (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
 scope_type VARCHAR(8) NOT NULL CHECK(scope_type IN ('UNIT','PLACE')),
 scope_key VARCHAR(64) NOT NULL,
 place_id VARCHAR(64),
 sms_enabled BOOLEAN,
 voice_enabled BOOLEAN,
 revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0),
 changed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CHECK ((scope_type='UNIT' AND place_id IS NULL AND scope_key=tenant_id) OR (scope_type='PLACE' AND place_id IS NOT NULL AND scope_key=place_id)),
 FOREIGN KEY(place_id,tenant_id) REFERENCES places(id,tenant_id),
 UNIQUE(tenant_id,scope_type,scope_key)
);
CREATE INDEX idx_notification_channel_policy_place ON notification_channel_policies(tenant_id,place_id);
ALTER TABLE notification_provider_attempts RENAME TO notification_provider_attempts_legacy;
CREATE TABLE notification_provider_attempts (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    alarm_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    contact_id VARCHAR(64) NOT NULL,
    channel VARCHAR(8) NOT NULL CHECK (channel IN ('SMS','VOICE')),
    attempt_no INTEGER NOT NULL CHECK (attempt_no > 0),
    occurrence_key VARCHAR(80) NOT NULL DEFAULT 'INITIAL',
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING','SENT','FAILED','UNKNOWN','SUPPRESSED','RESOURCE_UNAVAILABLE')),
    provider_code VARCHAR(64),
    provider_id VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (alarm_id, occurrence_key, contact_id, channel, attempt_no)
);
INSERT INTO notification_provider_attempts(id,tenant_id,alarm_id,device_id,contact_id,channel,attempt_no,occurrence_key,status,provider_code,provider_id,created_at,finished_at)
SELECT id,tenant_id,alarm_id,device_id,contact_id,channel,attempt_no,'INITIAL',status,provider_code,provider_id,created_at,finished_at FROM notification_provider_attempts_legacy;
DROP TABLE notification_provider_attempts_legacy;
CREATE INDEX idx_notification_provider_attempts_tenant ON notification_provider_attempts(tenant_id, created_at DESC);
CREATE INDEX idx_notification_provider_attempts_alarm ON notification_provider_attempts(alarm_id, contact_id, channel);
CREATE INDEX idx_notification_provider_occurrence ON notification_provider_attempts(tenant_id,alarm_id,occurrence_key,contact_id,channel,attempt_no);
