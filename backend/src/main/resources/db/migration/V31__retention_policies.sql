-- N16：数据保留与清理。默认不启用；30/90 天等值只能由有权限人员显式配置，切片不重置既有确认值。
CREATE TABLE retention_policies (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
 kind VARCHAR(24) NOT NULL CHECK(kind IN ('RAW_MESSAGES','DEVICE_COMMANDS','AUDIT_LOG')),
 keep_days INTEGER NOT NULL CHECK(keep_days BETWEEN 1 AND 3650),
 enabled BOOLEAN NOT NULL,
 updated_by VARCHAR(64) NOT NULL REFERENCES app_users(id),
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 UNIQUE(tenant_id,kind)
);

CREATE TABLE retention_runs (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
 kind VARCHAR(24) NOT NULL,
 keep_days INTEGER NOT NULL,
 cutoff_at TIMESTAMP WITH TIME ZONE NOT NULL,
 dry_run BOOLEAN NOT NULL,
 scanned BIGINT NOT NULL,
 deleted BIGINT NOT NULL,
 actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_retention_runs_tenant ON retention_runs(tenant_id,created_at,id);
