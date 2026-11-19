CREATE TABLE data_transfer_plans (
  id VARCHAR(64) PRIMARY KEY,
  tenant_id VARCHAR(64) NULL REFERENCES tenants(id),
  actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
  kind VARCHAR(40) NOT NULL,
  format VARCHAR(8) NOT NULL CHECK (format IN ('CSV','XLSX')),
  source_sha256 VARCHAR(64) NOT NULL,
  payload TEXT NOT NULL,
  payload_nonce VARCHAR(32) NOT NULL,
  row_count INTEGER NOT NULL CHECK (row_count BETWEEN 0 AND 10000),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  status VARCHAR(32) NOT NULL CHECK (status IN ('PREVIEWED','CONFIRMED','CONSUMED','EXPIRED'))
);
CREATE INDEX idx_data_transfer_plans_actor ON data_transfer_plans(actor_id,tenant_id,created_at);

CREATE TABLE data_transfer_jobs (
  id VARCHAR(64) PRIMARY KEY,
  plan_id VARCHAR(64) NOT NULL UNIQUE REFERENCES data_transfer_plans(id),
  tenant_id VARCHAR(64) NULL REFERENCES tenants(id),
  actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
  kind VARCHAR(40) NOT NULL,
  status VARCHAR(32) NOT NULL CHECK (status IN ('QUEUED','RUNNING','COMPLETED','COMPLETED_WITH_ERRORS','RECONFIRM_REQUIRED','CANCELLED')),
  row_count INTEGER NOT NULL,
  success_count INTEGER NOT NULL DEFAULT 0,
  error_count INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_data_transfer_jobs_actor ON data_transfer_jobs(actor_id,tenant_id,created_at);

CREATE TABLE data_transfer_job_rows (
  job_id VARCHAR(64) NOT NULL REFERENCES data_transfer_jobs(id),
  row_no INTEGER NOT NULL,
  status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING','SUCCEEDED','FAILED','INVALID')),
  result_id VARCHAR(128),
  errors TEXT NOT NULL,
  PRIMARY KEY (job_id,row_no)
);

CREATE TABLE data_transfer_exports (
  id VARCHAR(64) PRIMARY KEY,
  tenant_id VARCHAR(64) NULL REFERENCES tenants(id),
  actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
  scope_fingerprint VARCHAR(64) NOT NULL,
  kind VARCHAR(40) NOT NULL,
  format VARCHAR(8) NOT NULL CHECK (format IN ('CSV','XLSX')),
  request_key VARCHAR(128) NOT NULL,
  request_fingerprint VARCHAR(64) NOT NULL,
  content_sha256 VARCHAR(64) NOT NULL,
  content TEXT NOT NULL,
  content_nonce VARCHAR(32) NOT NULL,
  row_count INTEGER NOT NULL CHECK (row_count BETWEEN 0 AND 10000),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE UNIQUE INDEX uq_data_transfer_export_request ON data_transfer_exports(actor_id,request_key);

CREATE TABLE data_transfer_control_requests (
  actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
  operation VARCHAR(16) NOT NULL CHECK (operation IN ('CONFIRM','RECONFIRM','CANCEL')),
  request_key VARCHAR(128) NOT NULL,
  job_id VARCHAR(64) NOT NULL REFERENCES data_transfer_jobs(id),
  request_fingerprint VARCHAR(64) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY(actor_id,operation,request_key)
);
