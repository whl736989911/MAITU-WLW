-- N08：通知屏蔽（单位/设备集），有期限、可取消、火警需显式二次确认才一并屏蔽。
CREATE TABLE device_suppressions (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
 kind VARCHAR(16) NOT NULL CHECK(kind='NOTIFICATION'),
 scope VARCHAR(16) NOT NULL CHECK(scope IN ('TENANT','DEVICES')),
 fire_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
 reason VARCHAR(1000) NOT NULL,
 starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
 ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
 status VARCHAR(16) NOT NULL CHECK(status IN ('ACTIVE','CANCELLED')),
 created_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 cancelled_at TIMESTAMP WITH TIME ZONE,
 cancelled_by VARCHAR(64) REFERENCES app_users(id),
 cancel_reason VARCHAR(1000),
 revision BIGINT NOT NULL DEFAULT 0,
 CHECK(ends_at > starts_at)
);
CREATE INDEX idx_suppression_active ON device_suppressions(tenant_id,status,ends_at);
CREATE TABLE device_suppression_members (
 suppression_id VARCHAR(64) NOT NULL REFERENCES device_suppressions(id),
 device_id VARCHAR(64) NOT NULL,
 tenant_id VARCHAR(64) NOT NULL,
 PRIMARY KEY(suppression_id,device_id),
 FOREIGN KEY(device_id,tenant_id) REFERENCES devices(id,tenant_id)
);
