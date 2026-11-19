-- P9a1 manual information only: no provider/device/binding or payment facts.
-- Current card and every maintenance revision retain independent manual fields.
CREATE TABLE sim_cards (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    iccid VARCHAR(64) NOT NULL CHECK (CHAR_LENGTH(iccid) BETWEEN 1 AND 64),
    carrier_name VARCHAR(200) NOT NULL,
    installation_form VARCHAR(16) NOT NULL CHECK (installation_form = 'REMOVABLE'),
    replaceability VARCHAR(16) NOT NULL CHECK (replaceability IN ('UNKNOWN','REPLACEABLE','NOT_REPLACEABLE')),
    management_party VARCHAR(16) NOT NULL CHECK (management_party IN ('UNKNOWN','PLATFORM','TENANT','MANUFACTURER','OTHER')),
    fee_responsibility VARCHAR(16) NOT NULL CHECK (fee_responsibility IN ('UNKNOWN','PLATFORM','TENANT','MANUFACTURER','OTHER')),
    reported_operator_status VARCHAR(16) NOT NULL CHECK (reported_operator_status IN ('UNKNOWN','ACTIVE','SUSPENDED','EXPIRED','CANCELLED','OTHER')),
    expires_on DATE,
    evidence_at TIMESTAMP(6) WITH TIME ZONE,
    source_note VARCHAR(1000) NOT NULL,
    information_source VARCHAR(16) NOT NULL CHECK (information_source = 'MANUAL'),
    revision BIGINT NOT NULL CHECK (revision BETWEEN 0 AND 9007199254740991),
    recorded_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_sim_cards_id CHECK (REGEXP_LIKE(id,'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')),
    CONSTRAINT uq_sim_cards_id_tenant UNIQUE(id,tenant_id),
    CONSTRAINT uq_sim_cards_tenant_iccid UNIQUE(tenant_id,iccid)
);
CREATE INDEX idx_sim_cards_tenant_id ON sim_cards(tenant_id,id);

-- Append-only through the application: SQL administrators are not tamper-proofed.
-- id is the change identity; card_id is the id exposed inside its Card snapshot.
CREATE TABLE sim_card_changes (
    id VARCHAR(36) PRIMARY KEY,
    card_id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    iccid VARCHAR(64) NOT NULL CHECK (CHAR_LENGTH(iccid) BETWEEN 1 AND 64),
    carrier_name VARCHAR(200) NOT NULL,
    installation_form VARCHAR(16) NOT NULL CHECK (installation_form = 'REMOVABLE'),
    replaceability VARCHAR(16) NOT NULL CHECK (replaceability IN ('UNKNOWN','REPLACEABLE','NOT_REPLACEABLE')),
    management_party VARCHAR(16) NOT NULL CHECK (management_party IN ('UNKNOWN','PLATFORM','TENANT','MANUFACTURER','OTHER')),
    fee_responsibility VARCHAR(16) NOT NULL CHECK (fee_responsibility IN ('UNKNOWN','PLATFORM','TENANT','MANUFACTURER','OTHER')),
    reported_operator_status VARCHAR(16) NOT NULL CHECK (reported_operator_status IN ('UNKNOWN','ACTIVE','SUSPENDED','EXPIRED','CANCELLED','OTHER')),
    expires_on DATE,
    evidence_at TIMESTAMP(6) WITH TIME ZONE,
    source_note VARCHAR(1000) NOT NULL,
    information_source VARCHAR(16) NOT NULL CHECK (information_source = 'MANUAL'),
    revision BIGINT NOT NULL CHECK (revision BETWEEN 0 AND 9007199254740991),
    recorded_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    operation VARCHAR(6) NOT NULL,
    actor_user_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    CONSTRAINT ck_sim_card_changes_id CHECK (REGEXP_LIKE(id,'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')),
    CONSTRAINT ck_sim_card_changes_operation CHECK ((operation = 'CREATE' AND revision = 0) OR (operation = 'EDIT' AND revision > 0)),
    CONSTRAINT fk_sim_card_changes_card_tenant FOREIGN KEY(card_id,tenant_id) REFERENCES sim_cards(id,tenant_id),
    CONSTRAINT uq_sim_card_changes_card_revision UNIQUE(card_id,revision)
);
CREATE INDEX idx_sim_card_changes_tenant_card_revision ON sim_card_changes(tenant_id,card_id,revision DESC);

-- One-time precise upgrade grant, not a startup regrant or a new default for others.
INSERT INTO user_permissions(user_id,permission)
SELECT u.id,'sim-cards:read' FROM app_users u
WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE
AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='sim-cards:read');
INSERT INTO user_permissions(user_id,permission)
SELECT u.id,'sim-cards:write' FROM app_users u
WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE
AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='sim-cards:write');
