-- Preserve parameters, revisions, and history. Group targets cannot cross units.
ALTER TABLE parameter_changes RENAME TO parameter_changes_v91;
ALTER TABLE parameter_overrides RENAME TO parameter_overrides_v91;

CREATE TABLE parameter_overrides (
 id VARCHAR(64) CONSTRAINT pk_parameter_overrides_v100 PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
 scope VARCHAR(16) NOT NULL CHECK(scope IN ('TENANT','PLACE','GROUP','DEVICE')),
 scope_id VARCHAR(64) NOT NULL,
 device_id VARCHAR(64),
 parameter_key VARCHAR(64) NOT NULL CHECK(parameter_key='offline_threshold_minutes'),
 override_value BIGINT CHECK(override_value BETWEEN 1 AND 525600),
 revision BIGINT NOT NULL CHECK(revision>=1),
 place_id VARCHAR(64),
 group_id VARCHAR(64),
 CONSTRAINT uq_parameter_overrides_scope_v100 UNIQUE(tenant_id,scope,scope_id,parameter_key),
 FOREIGN KEY(device_id,tenant_id) REFERENCES devices(id,tenant_id),
 FOREIGN KEY(place_id,tenant_id) REFERENCES places(id,tenant_id),
 FOREIGN KEY(group_id,tenant_id) REFERENCES device_groups(id,tenant_id),
 CHECK((scope='TENANT' AND scope_id=tenant_id AND device_id IS NULL AND place_id IS NULL AND group_id IS NULL)
    OR (scope='DEVICE' AND device_id IS NOT NULL AND device_id=scope_id AND place_id IS NULL AND group_id IS NULL)
    OR (scope='PLACE' AND place_id IS NOT NULL AND place_id=scope_id AND device_id IS NULL AND group_id IS NULL)
    OR (scope='GROUP' AND group_id IS NOT NULL AND group_id=scope_id AND device_id IS NULL AND place_id IS NULL))
);
CREATE TABLE parameter_changes (
 id VARCHAR(64) CONSTRAINT pk_parameter_changes_v100 PRIMARY KEY,
 override_id VARCHAR(64) NOT NULL REFERENCES parameter_overrides(id),
 tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
 scope VARCHAR(16) NOT NULL CHECK(scope IN ('TENANT','PLACE','GROUP','DEVICE')),
 scope_id VARCHAR(64) NOT NULL,
 parameter_key VARCHAR(64) NOT NULL CHECK(parameter_key='offline_threshold_minutes'),
 action VARCHAR(16) NOT NULL CHECK(action IN ('SET','CLEAR')),
 previous_value BIGINT CHECK(previous_value BETWEEN 1 AND 525600),
 new_value BIGINT CHECK(new_value BETWEEN 1 AND 525600),
 revision_before BIGINT NOT NULL CHECK(revision_before>=0),
 revision_after BIGINT NOT NULL CHECK(revision_after=revision_before+1),
 actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
 reason VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT uq_parameter_changes_revision_v100 UNIQUE(override_id,revision_after),
 CHECK((action='SET' AND new_value IS NOT NULL) OR (action='CLEAR' AND new_value IS NULL AND previous_value IS NOT NULL))
);
INSERT INTO parameter_overrides(id,tenant_id,scope,scope_id,device_id,parameter_key,override_value,revision,place_id)
SELECT id,tenant_id,scope,scope_id,device_id,parameter_key,override_value,revision,place_id FROM parameter_overrides_v91;
INSERT INTO parameter_changes(id,override_id,tenant_id,scope,scope_id,parameter_key,action,previous_value,new_value,revision_before,revision_after,actor_id,reason,created_at)
SELECT id,override_id,tenant_id,scope,scope_id,parameter_key,action,previous_value,new_value,revision_before,revision_after,actor_id,reason,created_at FROM parameter_changes_v91;
DROP TABLE parameter_changes_v91;
DROP TABLE parameter_overrides_v91;
CREATE INDEX idx_parameter_history ON parameter_changes(tenant_id,scope,scope_id,parameter_key,created_at,id);
