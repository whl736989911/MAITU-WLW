-- Keep existing real and historical notification rules at unit scope.
ALTER TABLE notification_rules ADD COLUMN scope_type VARCHAR(8) NOT NULL DEFAULT 'UNIT';
ALTER TABLE notification_rules ADD COLUMN scope_place_id VARCHAR(64);
ALTER TABLE notification_rules ADD COLUMN scope_device_id VARCHAR(64);
ALTER TABLE notification_rules ADD CONSTRAINT ck_notification_rule_scope CHECK (
    (scope_type='UNIT' AND scope_place_id IS NULL AND scope_device_id IS NULL)
    OR (scope_type='PLACE' AND scope_place_id IS NOT NULL AND scope_device_id IS NULL)
    OR (scope_type='DEVICE' AND scope_place_id IS NULL AND scope_device_id IS NOT NULL)
);
ALTER TABLE notification_rules ADD CONSTRAINT fk_notification_rule_place
    FOREIGN KEY (scope_place_id, tenant_id) REFERENCES places(id, tenant_id);
ALTER TABLE notification_rules ADD CONSTRAINT fk_notification_rule_device
    FOREIGN KEY (scope_device_id, tenant_id) REFERENCES devices(id, tenant_id);
CREATE INDEX idx_notification_rule_scope
    ON notification_rules(tenant_id, event_scope, scope_type, scope_place_id, scope_device_id);
