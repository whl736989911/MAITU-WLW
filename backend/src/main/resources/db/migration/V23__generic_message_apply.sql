-- N06：通用消息应用、顺序水位与事务 outbox。全部为新增列/表，不改旧字段语义。
ALTER TABLE raw_messages ADD COLUMN sequence_no BIGINT;
ALTER TABLE raw_messages ADD COLUMN occurred_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE raw_messages ADD COLUMN application_state VARCHAR(16) NOT NULL DEFAULT 'NOT_APPLIED';
ALTER TABLE raw_messages ADD COLUMN applied_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE raw_messages ADD COLUMN apply_reason VARCHAR(1000);
ALTER TABLE raw_messages ADD COLUMN apply_error VARCHAR(64);
ALTER TABLE raw_messages ADD CONSTRAINT chk_raw_application_state CHECK (application_state IN ('NOT_APPLIED','APPLIED','STALE','REJECTED'));
ALTER TABLE raw_messages ADD CONSTRAINT chk_raw_sequence_nonnegative CHECK (sequence_no IS NULL OR sequence_no >= 0);
-- business_status 保持冻结语义（恒为 NOT_APPLIED），N06 生命周期只由 application_state 承载。

CREATE TABLE device_message_watermark (
  device_id VARCHAR(64) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  last_sequence BIGINT,
  last_occurred_at TIMESTAMP WITH TIME ZONE,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE message_outbox (
  id VARCHAR(64) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  raw_message_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  payload_json TEXT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  published_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_message_outbox_pending ON message_outbox(created_at, id);
