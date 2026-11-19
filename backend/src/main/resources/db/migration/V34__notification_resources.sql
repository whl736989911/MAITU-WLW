CREATE TABLE notification_resource_accounts (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    owner_type VARCHAR(16) NOT NULL CHECK (owner_type IN ('ORGANIZATION','PLACE')),
    owner_id VARCHAR(64) NOT NULL,
    owner_name VARCHAR(200) NOT NULL,
    sms_total BIGINT NOT NULL DEFAULT 0 CHECK (sms_total >= 0),
    sms_used BIGINT NOT NULL DEFAULT 0 CHECK (sms_used >= 0),
    voice_total BIGINT NOT NULL DEFAULT 0 CHECK (voice_total >= 0),
    voice_used BIGINT NOT NULL DEFAULT 0 CHECK (voice_used >= 0),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (tenant_id, owner_type, owner_id)
);
CREATE INDEX idx_notification_resource_accounts_tenant ON notification_resource_accounts(tenant_id, owner_type, owner_name);
CREATE TABLE notification_resource_ledger (
    id VARCHAR(64) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL REFERENCES notification_resource_accounts(id),
    tenant_id VARCHAR(64) NOT NULL,
    channel VARCHAR(8) NOT NULL CHECK (channel IN ('SMS','VOICE')),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('RECHARGE','ADJUST','CONSUME','REFUND')),
    quantity BIGINT NOT NULL CHECK (quantity <> 0),
    balance_after BIGINT NOT NULL,
    source VARCHAR(16) NOT NULL CHECK (source IN ('ADMIN','QR','SYSTEM')),
    reference VARCHAR(200),
    operator VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_notification_resource_ledger_account ON notification_resource_ledger(tenant_id, account_id, created_at);
CREATE TABLE notification_resource_orders (
    id VARCHAR(64) PRIMARY KEY,
    token VARCHAR(64) NOT NULL UNIQUE,
    tenant_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL REFERENCES notification_resource_accounts(id),
    channel VARCHAR(8) NOT NULL CHECK (channel IN ('SMS','VOICE')),
    quantity BIGINT NOT NULL CHECK (quantity > 0),
    amount_cents BIGINT NOT NULL CHECK (amount_cents >= 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','PAID','CANCELLED','FAILED')),
    created_by VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    paid_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_notification_resource_orders_tenant ON notification_resource_orders(tenant_id, created_at);
