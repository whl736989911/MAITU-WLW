-- N10：设备命令中心（受控模拟适配器）。六态、尝试历史、仅幂等重发，不伪造硬件执行。
CREATE TABLE device_commands (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
 device_id VARCHAR(64) NOT NULL,
 command_type VARCHAR(40) NOT NULL CHECK(command_type IN ('SIMULATED_COMMAND')),
 scenario VARCHAR(16) NOT NULL CHECK(scenario IN ('SUCCESS','REJECT','TIMEOUT','UNCERTAIN')),
 reason VARCHAR(1000) NOT NULL,
 status VARCHAR(16) NOT NULL CHECK(status IN ('PENDING','DISPATCHED','ACKED','REJECTED','TIMEOUT','UNCERTAIN')),
 attempt_count INTEGER NOT NULL DEFAULT 0,
 max_attempts INTEGER NOT NULL CHECK(max_attempts BETWEEN 1 AND 5),
 actor_id VARCHAR(64) NOT NULL REFERENCES app_users(id),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 dispatched_at TIMESTAMP WITH TIME ZONE,
 finished_at TIMESTAMP WITH TIME ZONE,
 last_error_code VARCHAR(64),
 revision BIGINT NOT NULL DEFAULT 0,
 FOREIGN KEY(device_id,tenant_id) REFERENCES devices(id,tenant_id)
);
CREATE INDEX idx_device_commands_tenant ON device_commands(tenant_id,created_at,id);

CREATE TABLE device_command_attempts (
 id VARCHAR(64) PRIMARY KEY,
 command_id VARCHAR(64) NOT NULL REFERENCES device_commands(id),
 attempt_no INTEGER NOT NULL,
 outcome VARCHAR(16) NOT NULL CHECK(outcome IN ('DISPATCHED','ACKED','REJECTED','TIMEOUT','UNCERTAIN')),
 error_code VARCHAR(64),
 started_at TIMESTAMP WITH TIME ZONE NOT NULL,
 finished_at TIMESTAMP WITH TIME ZONE,
 UNIQUE(command_id,attempt_no)
);
