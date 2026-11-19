-- Safe invalidations only. No business foreign keys, historical backfill, or insert-time sequence.
CREATE TABLE alarm_change_publisher_coord (
 id INTEGER PRIMARY KEY CHECK(id=1)
);
INSERT INTO alarm_change_publisher_coord(id) VALUES (1);
CREATE TABLE alarm_change_heads (
 tenant_id VARCHAR(64) PRIMARY KEY,
 stream_id VARCHAR(36) NOT NULL UNIQUE,
 last_sequence BIGINT NOT NULL CHECK(last_sequence>=0)
);
CREATE TABLE alarm_change_outbox (
 id VARCHAR(36) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
 publication_sequence BIGINT CHECK(publication_sequence>0),
 UNIQUE(tenant_id,publication_sequence)
);
CREATE INDEX idx_alarm_change_pending ON alarm_change_outbox(publication_sequence,recorded_at,id);
