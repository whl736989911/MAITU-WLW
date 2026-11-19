CREATE TABLE real_notification_completions (
    tenant_id VARCHAR(64) NOT NULL,
    source_kind VARCHAR(16) NOT NULL CHECK (source_kind IN ('ALARM','LEGACY_FAULT','RAW_FAULT')),
    source_id VARCHAR(64) NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, source_kind, source_id)
);
