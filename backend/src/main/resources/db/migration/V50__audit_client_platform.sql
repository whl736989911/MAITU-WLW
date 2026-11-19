ALTER TABLE audit_log ADD COLUMN client_platform VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_client_platform CHECK (client_platform IN ('WEB','APP','API','SYSTEM','UNKNOWN'));
