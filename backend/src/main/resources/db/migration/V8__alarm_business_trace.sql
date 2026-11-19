ALTER TABLE alarms ADD CONSTRAINT uq_alarm_trace_target UNIQUE(tenant_id,id);
CREATE TABLE alarm_trace_entries (
 id VARCHAR(128) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 alarm_id VARCHAR(64) NOT NULL,
 action VARCHAR(32) NOT NULL CHECK(action IN ('FIRE_CREATED','REVIEWED','ALARM_RECOVERED','DEVICE_RECOVERED','MIGRATION_BASELINE')),
 revision_before BIGINT,
 revision_after BIGINT NOT NULL CHECK(revision_after>=0),
 before_physical_state VARCHAR(16) CHECK(before_physical_state IN ('FIRE','NORMAL')),
 before_response_state VARCHAR(16) CHECK(before_response_state IN ('UNHANDLED','HANDLED')),
 before_conclusion VARCHAR(32) CHECK(before_conclusion IN ('TRUE_FIRE','FALSE_ALARM','TEST','DEVICE_ALARM')),
 after_physical_state VARCHAR(16) NOT NULL CHECK(after_physical_state IN ('FIRE','NORMAL')),
 after_response_state VARCHAR(16) NOT NULL CHECK(after_response_state IN ('UNHANDLED','HANDLED')),
 after_conclusion VARCHAR(32) CHECK(after_conclusion IN ('TRUE_FIRE','FALSE_ALARM','TEST','DEVICE_ALARM')),
 occurred_at TIMESTAMP WITH TIME ZONE,
 recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
 actor_kind VARCHAR(32) NOT NULL CHECK(actor_kind IN ('PLATFORM','CUSTOMER','SYSTEM','LEGACY_UNKNOWN')),
 actor_id VARCHAR(64) REFERENCES app_users(id),
 actor_name VARCHAR(200),
 remark VARCHAR(2000),
 origin VARCHAR(32) NOT NULL CHECK(origin IN ('APPLICATION','MIGRATION_SNAPSHOT')),
 UNIQUE(tenant_id,alarm_id,revision_after),
 FOREIGN KEY(tenant_id,alarm_id) REFERENCES alarms(tenant_id,id),
 CHECK((after_response_state='UNHANDLED' AND after_conclusion IS NULL) OR (after_response_state='HANDLED' AND after_conclusion IS NOT NULL)),
 CHECK((revision_before IS NULL AND before_physical_state IS NULL AND before_response_state IS NULL AND before_conclusion IS NULL) OR (revision_before IS NOT NULL AND revision_before>=0 AND before_physical_state IS NOT NULL AND before_response_state IS NOT NULL AND ((before_response_state='UNHANDLED' AND before_conclusion IS NULL) OR (before_response_state='HANDLED' AND before_conclusion IS NOT NULL)))),
 CHECK((actor_kind IN ('SYSTEM','LEGACY_UNKNOWN') AND actor_id IS NULL AND actor_name IS NULL) OR (actor_kind IN ('PLATFORM','CUSTOMER') AND actor_id IS NOT NULL AND actor_name IS NOT NULL)),
 CHECK((action='MIGRATION_BASELINE' AND origin='MIGRATION_SNAPSHOT' AND revision_before IS NULL AND occurred_at IS NULL AND actor_kind='LEGACY_UNKNOWN') OR (action<>'MIGRATION_BASELINE' AND origin='APPLICATION' AND actor_kind<>'LEGACY_UNKNOWN')),
 CHECK((action IN ('FIRE_CREATED','MIGRATION_BASELINE') AND revision_before IS NULL) OR (action NOT IN ('FIRE_CREATED','MIGRATION_BASELINE') AND revision_before IS NOT NULL AND revision_after=revision_before+1)),
 CHECK(action<>'FIRE_CREATED' OR (revision_after=0 AND after_physical_state='FIRE' AND after_response_state='UNHANDLED' AND after_conclusion IS NULL)),
 CHECK(action<>'REVIEWED' OR (after_response_state='HANDLED' AND before_physical_state=after_physical_state)),
 CHECK(action NOT IN ('ALARM_RECOVERED','DEVICE_RECOVERED') OR (before_physical_state='FIRE' AND after_physical_state='NORMAL' AND before_response_state=after_response_state AND before_conclusion IS NOT DISTINCT FROM after_conclusion AND remark IS NULL))
);
CREATE INDEX idx_alarm_trace_page ON alarm_trace_entries(tenant_id,alarm_id,revision_after,id);
INSERT INTO alarm_trace_entries(id,tenant_id,alarm_id,action,revision_after,after_physical_state,after_response_state,after_conclusion,recorded_at,actor_kind,remark,origin)
 SELECT 'baseline-' || id,tenant_id,id,'MIGRATION_BASELINE',revision,physical_state,response_state,conclusion,CURRENT_TIMESTAMP,'LEGACY_UNKNOWN',remark,'MIGRATION_SNAPSHOT' FROM alarms;
INSERT INTO user_permissions(user_id,permission) SELECT id,'alarm:history' FROM app_users u WHERE u.id='bootstrap-admin' AND u.user_type='PLATFORM' AND u.is_protected=TRUE AND NOT EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission='alarm:history');
