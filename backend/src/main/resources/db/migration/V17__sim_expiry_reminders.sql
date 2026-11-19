-- N03 expiry reminders: independent workflow facts; no changes to SIM source ledgers.
CREATE TABLE sim_expiry_config (
    tenant_id VARCHAR(64) PRIMARY KEY REFERENCES tenants(id),
    enabled BOOLEAN NOT NULL,
    timezone VARCHAR(128),
    lead_days INTEGER,
    repeat_interval_days INTEGER,
    repeat_count INTEGER,
    revision BIGINT NOT NULL CHECK (revision BETWEEN 1 AND 9007199254740991),
    updated_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT ck_sim_expiry_config_complete CHECK (
        (timezone IS NULL AND lead_days IS NULL AND repeat_interval_days IS NULL AND repeat_count IS NULL AND enabled=FALSE)
        OR (timezone IS NOT NULL AND lead_days IS NOT NULL AND lead_days>=0 AND repeat_interval_days IS NOT NULL AND repeat_interval_days>=1 AND repeat_count IS NOT NULL AND repeat_count>=0)
    )
);
CREATE TABLE sim_expiry_recipients (
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    user_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    added_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    added_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
    PRIMARY KEY(tenant_id,user_id)
);
CREATE INDEX idx_sim_expiry_recipients_tenant_added ON sim_expiry_recipients(tenant_id,added_at,user_id);
CREATE TABLE sim_expiry_rounds (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    target_kind VARCHAR(16) NOT NULL CHECK (target_kind IN ('CARD','EMBEDDED')),
    target_id VARCHAR(64) NOT NULL,
    expires_on DATE NOT NULL,
    generation INTEGER NOT NULL CHECK (generation>=1),
    state VARCHAR(16) NOT NULL CHECK (state IN ('OPEN','HANDLED','SUPERSEDED')),
    revision BIGINT NOT NULL CHECK (revision BETWEEN 1 AND 9007199254740991),
    reminder_count BIGINT NOT NULL CHECK (reminder_count>=1),
    last_reminded_on DATE NOT NULL,
    opened_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    source_revision BIGINT NOT NULL CHECK (source_revision>=0),
    handled_at TIMESTAMP(6) WITH TIME ZONE,
    handled_by VARCHAR(64) REFERENCES app_users(id),
    handle_reason VARCHAR(1000),
    reopened_from VARCHAR(36) REFERENCES sim_expiry_rounds(id),
    reopen_reason VARCHAR(1000),
    CONSTRAINT ck_sim_expiry_rounds_id CHECK (REGEXP_LIKE(id,'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')),
    CONSTRAINT uq_sim_expiry_round_generation UNIQUE(tenant_id,target_kind,target_id,expires_on,generation),
    CONSTRAINT ck_sim_expiry_round_handled CHECK ((state='HANDLED' AND handled_at IS NOT NULL AND handled_by IS NOT NULL AND handle_reason IS NOT NULL) OR (state<>'HANDLED' AND handled_at IS NULL AND handled_by IS NULL AND handle_reason IS NULL))
);
CREATE INDEX idx_sim_expiry_rounds_tenant_state_date ON sim_expiry_rounds(tenant_id,state,expires_on,id);
CREATE INDEX idx_sim_expiry_rounds_target_date ON sim_expiry_rounds(tenant_id,target_kind,target_id,expires_on,generation DESC);
CREATE TABLE sim_expiry_notices (
    id VARCHAR(36) PRIMARY KEY,
    round_id VARCHAR(36) NOT NULL REFERENCES sim_expiry_rounds(id),
    sequence BIGINT NOT NULL CHECK (sequence>=1),
    noticed_on DATE NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('INITIAL','REPEAT')),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_sim_expiry_notices_id CHECK (REGEXP_LIKE(id,'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')),
    CONSTRAINT uq_sim_expiry_notices_sequence UNIQUE(round_id,sequence),
    CONSTRAINT uq_sim_expiry_notices_date UNIQUE(round_id,noticed_on),
    CONSTRAINT ck_sim_expiry_notices_sequence_kind CHECK ((sequence=1 AND kind='INITIAL') OR (sequence>1 AND kind='REPEAT'))
);
CREATE TABLE sim_expiry_reads (
    round_id VARCHAR(36) NOT NULL REFERENCES sim_expiry_rounds(id),
    user_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    read_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY(round_id,user_id)
);
INSERT INTO user_permissions(user_id,permission)
SELECT u.id,'sim-expiry:read' FROM app_users u
WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE
AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='sim-expiry:read');
INSERT INTO user_permissions(user_id,permission)
SELECT u.id,'sim-expiry:handle' FROM app_users u
WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE
AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='sim-expiry:handle');
INSERT INTO user_permissions(user_id,permission)
SELECT u.id,'sim-expiry:write' FROM app_users u
WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE
AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='sim-expiry:write');
