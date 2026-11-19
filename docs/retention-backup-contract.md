# N16：数据保留与备份恢复（Frozen v1，实施合同）

## 目标与边界

生产数据治理的两块可执行能力：**显式保留策略**与**可执行的备份恢复演练**。

- 保留策略默认**未配置 = 不启用**；30/90 天等既有确认值只能由有权限人员显式写入，本切片不重置、不猜测。
- 清理只按显式策略执行，先试算再执行，每次运行留痕。
- 备份恢复演练只声称**H2 已验证**；工具链未提供 `pg_dump/pg_basebackup`，PG 备份不在本切片声称通过。

## 保留策略（V31）

`retention_policies(tenant_id, kind, keep_days, enabled, updated_by, updated_at, revision)`，`kind ∈ {RAW_MESSAGES, DEVICE_COMMANDS, AUDIT_LOG}`。
`retention_runs(id, tenant_id, kind, keep_days, cutoff_at, dry_run, scanned, deleted, actor_id, created_at)`。

删除规则：

| kind | 范围与保护 |
|---|---|
| `RAW_MESSAGES` | 删除 `received_at < cutoff` 的原文；先删其 `message_previews`；**存在处理任务或重解析任务的消息一律不删** |
| `DEVICE_COMMANDS` | 仅删终态（`ACKED/REJECTED/TIMEOUT/UNCERTAIN`）且超期的命令与尝试；`PENDING/DISPATCHED` 永不删除 |
| `AUDIT_LOG` | 删除 `occurred_at < cutoff` 的审计行 |

API（管理端，`retention:read/write`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/management/retention/policies` | 当前策略 |
| POST | `/api/management/retention/policies` | `{kind, keep_days, enabled, actor_password}` |
| POST | `/api/management/retention/purge` | `{kind, dry_run, actor_password}`，返回 `scanned/deleted` |
| GET | `/api/management/retention/runs?limit=` | 运行历史 |

未配置 → 409 `RETENTION_NOT_CONFIGURED`；未启用 → 409 `RETENTION_DISABLED`；两种情况都不删除任何数据。

## 备份恢复演练

`scripts/dev/backup-restore-drill.sh`：

1. 启动 H2 实例并写入种子（设备/来源/消息）；
2. 停止实例，复制 `*.mv.db` 作为备份，计算 SHA-256；
3. 用副本启动实例（`--app.development=false`，不重建演示数据/账号）；
4. 通过 API 读取设备总数并与源实例比较；
5. 输出 `{ok, source_devices, restored_devices, backup_sha256, restored_sha256, storage, pg_backup_verified}`。

实测结果：`ok=true`，3 = 3，SHA 相同；`pg_backup_verified=false`（工具链无 PG 备份工具，不冒认）。
