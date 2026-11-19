-- N07：重解析任务。纯解析，无业务写入；租约/尝试/崩溃恢复可追溯。
CREATE TABLE reparse_jobs (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
 raw_message_id VARCHAR(64) NOT NULL REFERENCES raw_messages(id),
 product_version_id VARCHAR(64) NOT NULL REFERENCES product_versions(id),
 actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
 reason VARCHAR(1000) NOT NULL,
 status VARCHAR(16) NOT NULL CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
 attempt_count INTEGER NOT NULL DEFAULT 0,
 max_attempts INTEGER NOT NULL CHECK(max_attempts BETWEEN 1 AND 20),
 available_at TIMESTAMP WITH TIME ZONE NOT NULL,
 lease_owner VARCHAR(64),
 lease_until TIMESTAMP WITH TIME ZONE,
 lease_token BIGINT NOT NULL DEFAULT 0,
 revision BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 started_at TIMESTAMP WITH TIME ZONE,
 finished_at TIMESTAMP WITH TIME ZONE,
 last_error_code VARCHAR(64),
 result_json TEXT,
 UNIQUE(raw_message_id)
);
CREATE INDEX idx_reparse_claim ON reparse_jobs(status,available_at,lease_until);
CREATE INDEX idx_reparse_tenant ON reparse_jobs(tenant_id,created_at,id);

CREATE TABLE reparse_attempts (
 id VARCHAR(64) PRIMARY KEY,
 job_id VARCHAR(64) NOT NULL REFERENCES reparse_jobs(id),
 attempt_no INTEGER NOT NULL,
 lease_token BIGINT NOT NULL,
 worker_id VARCHAR(64) NOT NULL,
 started_at TIMESTAMP WITH TIME ZONE NOT NULL,
 finished_at TIMESTAMP WITH TIME ZONE,
 outcome VARCHAR(16) NOT NULL CHECK(outcome IN ('RUNNING','SUCCEEDED','FAILED','EXPIRED')),
 error_code VARCHAR(64),
 result_json TEXT,
 UNIQUE(job_id,attempt_no)
);
