-- N12：批量导入设备与业务快照导出。导入按整批校验、整批成败；导出为不可变快照。
CREATE TABLE device_import_jobs (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
 source_sha256 VARCHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL CHECK(status IN ('CONFIRMED','REJECTED')),
 row_count INTEGER NOT NULL,
 ok_count INTEGER NOT NULL,
 error_count INTEGER NOT NULL,
 actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
 reason VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_device_import_jobs_tenant ON device_import_jobs(tenant_id,created_at,id);

CREATE TABLE device_import_rows (
 job_id VARCHAR(64) NOT NULL REFERENCES device_import_jobs(id),
 row_no INTEGER NOT NULL,
 name VARCHAR(200) NOT NULL,
 device_eui VARCHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL CHECK(status IN ('OK','ERROR')),
 error_code VARCHAR(64),
 device_id VARCHAR(64),
 PRIMARY KEY(job_id,row_no)
);

CREATE TABLE device_export_snapshots (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
 kind VARCHAR(16) NOT NULL CHECK(kind IN ('DEVICES')),
 content TEXT NOT NULL,
 content_sha256 VARCHAR(64) NOT NULL,
 row_count INTEGER NOT NULL,
 actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
 reason VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_device_export_tenant ON device_export_snapshots(tenant_id,created_at,id);
