-- Page-managed WeChat Pay APIv2 configuration. Secrets are stored encrypted and are write-only through the API.
CREATE TABLE payment_wechat_config (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    configured BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    mch_id VARCHAR(64),
    app_id VARCHAR(64),
    api_v2_key_cipher TEXT,
    api_v2_key_nonce VARCHAR(64),
    api_v2_key_masked VARCHAR(64),
    notify_url VARCHAR(500) NOT NULL DEFAULT '',
    order_expiry_minutes INTEGER CHECK (order_expiry_minutes BETWEEN 1 AND 21600),
    auto_lifecycle_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    auto_reconcile_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    credentials_revision BIGINT NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE,
    updated_by VARCHAR(64)
);
INSERT INTO payment_wechat_config (id) VALUES (1);

-- Retained APIv2 keys so a late callback or query for an order created before a key rotation still verifies.
CREATE TABLE payment_wechat_key_history (
    revision BIGINT PRIMARY KEY,
    api_v2_key_cipher TEXT NOT NULL,
    api_v2_key_nonce VARCHAR(64) NOT NULL,
    api_v2_key_masked VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by VARCHAR(64)
);

-- Merchant identity snapshot and explicit lifecycle state per local order.
ALTER TABLE notification_resource_orders ADD COLUMN merchant_config_revision BIGINT;
ALTER TABLE notification_resource_orders ADD COLUMN merchant_mch_id VARCHAR(64);
ALTER TABLE notification_resource_orders ADD COLUMN merchant_app_id VARCHAR(64);
ALTER TABLE notification_resource_orders ADD COLUMN payment_state VARCHAR(24);
ALTER TABLE notification_resource_orders ADD COLUMN payment_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE notification_resource_orders ADD COLUMN last_payment_attempt_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE notification_resource_orders ADD COLUMN close_state VARCHAR(24);
ALTER TABLE notification_resource_orders ADD COLUMN close_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE notification_resource_orders ADD COLUMN last_close_attempt_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE notification_resource_orders ADD COLUMN query_state VARCHAR(24);
ALTER TABLE notification_resource_orders ADD COLUMN query_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE notification_resource_orders ADD COLUMN last_query_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE notification_resource_orders ADD COLUMN closed_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_notification_resource_orders_pending_expiry ON notification_resource_orders(status, expires_at);
CREATE INDEX idx_notification_resource_orders_pending_query ON notification_resource_orders(status, last_query_at);

-- Honest backfill only: no order gains a payment, expiry, close or query fact it did not already have.
UPDATE notification_resource_orders SET payment_state = CASE
    WHEN status = 'PAID' THEN 'PAID'
    WHEN code_url IS NOT NULL THEN 'ACCEPTED'
    WHEN payment_provider = 'WECHAT_NATIVE_FAILED' THEN 'UNKNOWN'
    ELSE 'CREATED' END
WHERE payment_state IS NULL;

-- Exactly-once payment credit evidence. The primary key is the provider transaction identity.
CREATE TABLE payment_paid_transactions (
    transaction_id VARCHAR(128) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL REFERENCES notification_resource_orders(id),
    tenant_id VARCHAR(64) NOT NULL,
    amount_cents BIGINT NOT NULL CHECK (amount_cents >= 0),
    source VARCHAR(32) NOT NULL,
    credited_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_payment_paid_transactions_order ON payment_paid_transactions(order_id);

-- Daily bill reconciliation is insert-only for observations; a run row finalizes exactly once.
CREATE TABLE payment_reconciliation_runs (
    id VARCHAR(64) PRIMARY KEY,
    bill_date VARCHAR(8) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    requested_by VARCHAR(64),
    idem_key VARCHAR(128),
    merchant_mch_id VARCHAR(64),
    bill_sha256 VARCHAR(64),
    bill_cipher TEXT,
    bill_nonce VARCHAR(64),
    local_orders INTEGER NOT NULL DEFAULT 0,
    bill_rows INTEGER NOT NULL DEFAULT 0,
    matched_rows INTEGER NOT NULL DEFAULT 0,
    external_rows INTEGER NOT NULL DEFAULT 0,
    difference_count INTEGER NOT NULL DEFAULT 0,
    malformed_rows INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    error_message VARCHAR(300),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE
);
CREATE UNIQUE INDEX ux_payment_reconciliation_runs_idem ON payment_reconciliation_runs(requested_by, idem_key);
CREATE INDEX idx_payment_reconciliation_runs_date ON payment_reconciliation_runs(bill_date, created_at);

CREATE TABLE payment_reconciliation_differences (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL REFERENCES payment_reconciliation_runs(id),
    kind VARCHAR(48) NOT NULL,
    order_id VARCHAR(64),
    out_trade_no VARCHAR(64),
    transaction_id VARCHAR(128),
    local_status VARCHAR(16),
    local_amount_cents BIGINT,
    bill_amount_cents BIGINT,
    bill_trade_state VARCHAR(24),
    detail VARCHAR(300),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_payment_reconciliation_differences_run ON payment_reconciliation_differences(run_id, kind, id);
