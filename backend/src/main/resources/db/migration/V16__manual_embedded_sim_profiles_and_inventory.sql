-- N02 independent manual records only, never physical cards/provider/association facts.
-- Virtual unregistered revision zero has no row. Every accepted new record is positive.
CREATE TABLE sim_embedded_profiles (
    device_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    iccid VARCHAR(64) CHECK (iccid IS NULL OR CHAR_LENGTH(iccid) BETWEEN 1 AND 64),
    carrier_name VARCHAR(200) NOT NULL,
    installation_form VARCHAR(16) NOT NULL CHECK (installation_form='EMBEDDED'),
    replaceability VARCHAR(16) NOT NULL CHECK (replaceability IN ('UNKNOWN','REPLACEABLE','NOT_REPLACEABLE')),
    management_party VARCHAR(16) NOT NULL CHECK (management_party IN ('UNKNOWN','PLATFORM','TENANT','MANUFACTURER','OTHER')),
    fee_responsibility VARCHAR(16) NOT NULL CHECK (fee_responsibility IN ('UNKNOWN','PLATFORM','TENANT','MANUFACTURER','OTHER')),
    reported_operator_status VARCHAR(16) NOT NULL CHECK (reported_operator_status IN ('UNKNOWN','ACTIVE','SUSPENDED','EXPIRED','CANCELLED','OTHER')),
    expires_on DATE,
    evidence_at TIMESTAMP(6) WITH TIME ZONE,
    source_note VARCHAR(1000) NOT NULL,
    information_source VARCHAR(16) NOT NULL CHECK (information_source='MANUAL'),
    revision BIGINT NOT NULL CHECK (revision BETWEEN 1 AND 9007199254740991),
    recorded_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_sim_embedded_profiles_device_tenant FOREIGN KEY(device_id,tenant_id) REFERENCES devices(id,tenant_id),
    CONSTRAINT uq_sim_embedded_profiles_device_tenant UNIQUE(device_id,tenant_id)
);

-- Full immutable manual snapshots; public nested Profile has no change identity/actor.
CREATE TABLE sim_embedded_profile_changes (
    id VARCHAR(36) PRIMARY KEY,
    device_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    iccid VARCHAR(64) CHECK (iccid IS NULL OR CHAR_LENGTH(iccid) BETWEEN 1 AND 64),
    carrier_name VARCHAR(200) NOT NULL,
    installation_form VARCHAR(16) NOT NULL CHECK (installation_form='EMBEDDED'),
    replaceability VARCHAR(16) NOT NULL CHECK (replaceability IN ('UNKNOWN','REPLACEABLE','NOT_REPLACEABLE')),
    management_party VARCHAR(16) NOT NULL CHECK (management_party IN ('UNKNOWN','PLATFORM','TENANT','MANUFACTURER','OTHER')),
    fee_responsibility VARCHAR(16) NOT NULL CHECK (fee_responsibility IN ('UNKNOWN','PLATFORM','TENANT','MANUFACTURER','OTHER')),
    reported_operator_status VARCHAR(16) NOT NULL CHECK (reported_operator_status IN ('UNKNOWN','ACTIVE','SUSPENDED','EXPIRED','CANCELLED','OTHER')),
    expires_on DATE,
    evidence_at TIMESTAMP(6) WITH TIME ZONE,
    source_note VARCHAR(1000) NOT NULL,
    information_source VARCHAR(16) NOT NULL CHECK (information_source='MANUAL'),
    revision BIGINT NOT NULL CHECK (revision BETWEEN 1 AND 9007199254740991),
    recorded_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    operation VARCHAR(6) NOT NULL CHECK (operation='RECORD'),
    reason VARCHAR(1000) NOT NULL,
    actor_user_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    CONSTRAINT ck_sim_embedded_profile_changes_id CHECK (REGEXP_LIKE(id,'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')),
    CONSTRAINT fk_sim_embedded_profile_changes_profile_tenant FOREIGN KEY(device_id,tenant_id) REFERENCES sim_embedded_profiles(device_id,tenant_id),
    CONSTRAINT uq_sim_embedded_profile_changes_device_revision UNIQUE(device_id,revision)
);
CREATE INDEX idx_sim_embedded_profile_changes_tenant_device_revision ON sim_embedded_profile_changes(tenant_id,device_id,revision DESC);

-- Inventory is independent of manual card metadata and current platform association.
CREATE TABLE sim_card_inventory (
    card_id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('AVAILABLE','ISSUED','SCRAPPED')),
    issued_to VARCHAR(1000) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision BETWEEN 1 AND 9007199254740991),
    recorded_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_sim_card_inventory_recipient CHECK ((status='ISSUED' AND CHAR_LENGTH(issued_to)>0) OR (status IN ('AVAILABLE','SCRAPPED') AND issued_to='')),
    CONSTRAINT fk_sim_card_inventory_card_tenant FOREIGN KEY(card_id,tenant_id) REFERENCES sim_cards(id,tenant_id),
    CONSTRAINT uq_sim_card_inventory_card_tenant UNIQUE(card_id,tenant_id)
);
CREATE INDEX idx_sim_card_inventory_tenant_card ON sim_card_inventory(tenant_id,card_id);
CREATE INDEX idx_sim_card_inventory_tenant_status_card ON sim_card_inventory(tenant_id,status,card_id);

-- Every fresh accepted manual registration appends one event, including same-state records.
CREATE TABLE sim_card_inventory_changes (
    id VARCHAR(36) PRIMARY KEY,
    card_id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision BETWEEN 1 AND 9007199254740991),
    operation VARCHAR(8) NOT NULL CHECK (operation IN ('RECEIVE','ISSUE','RECOVER','SCRAP','CORRECT')),
    from_status VARCHAR(16) NOT NULL CHECK (from_status IN ('UNREGISTERED','AVAILABLE','ISSUED','SCRAPPED')),
    to_status VARCHAR(16) NOT NULL CHECK (to_status IN ('AVAILABLE','ISSUED','SCRAPPED')),
    issued_to VARCHAR(1000) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    recorded_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    actor_user_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    CONSTRAINT ck_sim_card_inventory_changes_id CHECK (REGEXP_LIKE(id,'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')),
    CONSTRAINT ck_sim_card_inventory_changes_first CHECK ((revision=1 AND from_status='UNREGISTERED') OR (revision>1 AND from_status<>'UNREGISTERED')),
    CONSTRAINT ck_sim_card_inventory_changes_transition CHECK ((operation IN ('RECEIVE','RECOVER') AND from_status<>'SCRAPPED' AND to_status='AVAILABLE') OR (operation='ISSUE' AND from_status<>'SCRAPPED' AND to_status='ISSUED') OR (operation='SCRAP' AND to_status='SCRAPPED') OR (operation='CORRECT' AND from_status='SCRAPPED' AND to_status='AVAILABLE')),
    CONSTRAINT ck_sim_card_inventory_changes_recipient CHECK ((to_status='ISSUED' AND CHAR_LENGTH(issued_to)>0) OR (to_status IN ('AVAILABLE','SCRAPPED') AND issued_to='')),
    CONSTRAINT fk_sim_card_inventory_changes_inventory_tenant FOREIGN KEY(card_id,tenant_id) REFERENCES sim_card_inventory(card_id,tenant_id),
    CONSTRAINT uq_sim_card_inventory_changes_card_revision UNIQUE(card_id,revision)
);
CREATE INDEX idx_sim_card_inventory_changes_tenant_card_revision ON sim_card_inventory_changes(tenant_id,card_id,revision DESC);

-- Exact one-time protected root upgrade grants; no other account/old right is touched.
INSERT INTO user_permissions(user_id,permission)
SELECT u.id,'sim-embedded:read' FROM app_users u
WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE
AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='sim-embedded:read');
INSERT INTO user_permissions(user_id,permission)
SELECT u.id,'sim-embedded:write' FROM app_users u
WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE
AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='sim-embedded:write');
INSERT INTO user_permissions(user_id,permission)
SELECT u.id,'sim-inventory:read' FROM app_users u
WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE
AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='sim-inventory:read');
INSERT INTO user_permissions(user_id,permission)
SELECT u.id,'sim-inventory:write' FROM app_users u
WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE
AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='sim-inventory:write');
