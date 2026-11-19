CREATE TABLE notification_recharge_policy (
    tenant_id VARCHAR(64) PRIMARY KEY REFERENCES tenants(id),
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('ORGANIZATION','GRANTS')),
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE,
    updated_by VARCHAR(64)
);
CREATE TABLE notification_recharge_grants (
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    user_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    owner_type VARCHAR(16) NOT NULL CHECK (owner_type IN ('ORGANIZATION','PLACE')),
    owner_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by VARCHAR(64),
    PRIMARY KEY (tenant_id, user_id, owner_type, owner_id)
);
ALTER TABLE notification_resource_orders ADD COLUMN payment_provider VARCHAR(32);
ALTER TABLE notification_resource_orders ADD COLUMN code_url VARCHAR(1024);
ALTER TABLE notification_resource_orders ADD COLUMN prepay_id VARCHAR(128);
ALTER TABLE notification_resource_orders ADD COLUMN transaction_id VARCHAR(128);
ALTER TABLE notification_resource_orders ADD COLUMN paid_amount_cents BIGINT;
ALTER TABLE notification_resource_orders ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE;
INSERT INTO user_permissions(user_id,permission)
SELECT id,'notification-recharge:read' FROM app_users WHERE user_type='CUSTOMER'
 AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=app_users.id AND p.permission='notification-recharge:read');
INSERT INTO user_permissions(user_id,permission)
SELECT id,'notification-recharge:write' FROM app_users WHERE user_type='CUSTOMER'
 AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=app_users.id AND p.permission='notification-recharge:write');
