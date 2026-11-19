-- Independent platform manual associations, never physical/network/provider facts.
-- Virtual revision zero has no row. Persisted empty states survive unbind to prevent ABA.
CREATE TABLE sim_card_associations (
    card_id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64),
    revision BIGINT NOT NULL CHECK (revision BETWEEN 1 AND 9007199254740991),
    recorded_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_sim_card_associations_card_tenant FOREIGN KEY(card_id,tenant_id) REFERENCES sim_cards(id,tenant_id),
    CONSTRAINT fk_sim_card_associations_device_tenant FOREIGN KEY(device_id,tenant_id) REFERENCES devices(id,tenant_id),
    CONSTRAINT ck_sim_card_associations_device CHECK (device_id IS NULL OR CHAR_LENGTH(device_id) BETWEEN 1 AND 64),
    CONSTRAINT uq_sim_card_associations_tenant_device UNIQUE(tenant_id,device_id)
);

-- Append-only through the application; SQL administrators are not tamper-proofed.
CREATE TABLE sim_card_association_changes (
    id VARCHAR(36) PRIMARY KEY,
    card_id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision BETWEEN 1 AND 9007199254740991),
    operation VARCHAR(6) NOT NULL,
    from_device_id VARCHAR(64),
    to_device_id VARCHAR(64),
    reason VARCHAR(1000) NOT NULL,
    recorded_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    actor_user_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
    CONSTRAINT ck_sim_card_association_changes_id CHECK (REGEXP_LIKE(id,'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')),
    CONSTRAINT ck_sim_card_association_changes_direction CHECK ((operation='BIND' AND from_device_id IS NULL AND to_device_id IS NOT NULL) OR (operation='UNBIND' AND from_device_id IS NOT NULL AND to_device_id IS NULL)),
    CONSTRAINT ck_sim_card_association_changes_from CHECK (from_device_id IS NULL OR CHAR_LENGTH(from_device_id) BETWEEN 1 AND 64),
    CONSTRAINT ck_sim_card_association_changes_to CHECK (to_device_id IS NULL OR CHAR_LENGTH(to_device_id) BETWEEN 1 AND 64),
    CONSTRAINT fk_sim_card_association_changes_card_tenant FOREIGN KEY(card_id,tenant_id) REFERENCES sim_cards(id,tenant_id),
    CONSTRAINT fk_sim_card_association_changes_from_tenant FOREIGN KEY(from_device_id,tenant_id) REFERENCES devices(id,tenant_id),
    CONSTRAINT fk_sim_card_association_changes_to_tenant FOREIGN KEY(to_device_id,tenant_id) REFERENCES devices(id,tenant_id),
    CONSTRAINT uq_sim_card_association_changes_card_revision UNIQUE(card_id,revision)
);
CREATE INDEX idx_sim_card_association_changes_tenant_card_revision ON sim_card_association_changes(tenant_id,card_id,revision DESC);

-- One-time exact protected bootstrap root grant, not a startup regrant or implicit delegation.
INSERT INTO user_permissions(user_id,permission)
SELECT u.id,'sim-cards:bind' FROM app_users u
WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE
AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='sim-cards:bind');
