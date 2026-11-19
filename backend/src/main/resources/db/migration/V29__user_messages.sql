-- N13：站内消息（按用户、独立已读/已处理）与个人会话视图。
CREATE TABLE user_messages (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
 user_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
 kind VARCHAR(24) NOT NULL CHECK(kind IN ('SYSTEM','ALARM')),
 title VARCHAR(200) NOT NULL,
 body VARCHAR(2000) NOT NULL,
 status VARCHAR(16) NOT NULL CHECK(status IN ('UNREAD','READ','HANDLED')),
 created_by VARCHAR(64) REFERENCES app_users(id),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 read_at TIMESTAMP WITH TIME ZONE,
 handled_at TIMESTAMP WITH TIME ZONE,
 revision BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_user_messages_owner ON user_messages(user_id,created_at,id);
CREATE INDEX idx_user_messages_tenant ON user_messages(tenant_id,created_at,id);

ALTER TABLE sessions ADD COLUMN created_at TIMESTAMP WITH TIME ZONE;
