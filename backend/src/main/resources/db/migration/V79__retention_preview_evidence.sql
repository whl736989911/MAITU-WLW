-- N16 follow-up: frozen retention previews and truthful execution counts.
-- Existing rows are intentionally not rewritten: legacy dry-run rows stored a potential count in deleted.
ALTER TABLE retention_runs ADD COLUMN eligible BIGINT;
ALTER TABLE retention_runs ADD COLUMN candidate_fingerprint VARCHAR(64);
ALTER TABLE retention_runs ADD COLUMN policy_revision BIGINT;
ALTER TABLE retention_runs ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE retention_runs ADD COLUMN source_preview_id VARCHAR(64);
ALTER TABLE retention_runs ADD COLUMN reason VARCHAR(1000);
ALTER TABLE retention_runs ADD COLUMN request_id VARCHAR(64);

CREATE INDEX idx_retention_runs_preview ON retention_runs(tenant_id,kind,created_at,id);
