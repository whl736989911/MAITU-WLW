# N07：重解析、重放与补偿（Frozen v1，实施合同）

## 目标与边界

对已捕获的原始消息做**纯解析重放**：使用捕获时固定的产品版本重新归一化，记录任务尝试与结果。默认无外部副作用。

- 不写设备、事件、报警、通知、命令，不改水位，不产生 outbox。
- 不重新绑定、不补版本、不触发自动策略。
- 真实厂商/运营商网络不在本切片。
- 重解析并发安全：同一消息同一时刻最多一个任务（`UNIQUE(raw_message_id)`）。

## 数据模型（V24）

`reparse_jobs`：`raw_message_id`（唯一）、`product_version_id`（捕获时版本快照）、`actor_id`、`reason`、`status`（PENDING/RUNNING/SUCCEEDED/FAILED）、`attempt_count`、`max_attempts=3`、`available_at`、`lease_owner/lease_until/lease_token`、`revision`、`created_at/started_at/finished_at`、`last_error_code`、`result_json`。

`reparse_attempts`：`attempt_no`、`lease_token`、`worker_id`、`started_at/finished_at`、`outcome`（RUNNING/SUCCEEDED/FAILED/EXPIRED）、`error_code`、`result_json`。

## 运行与恢复

- 创建只排队：`POST /api/management/messages/{id}/reparse`，权限 `messages:reparse`，需二次确认密码；已存在任务返回 409 `REPARSE_ALREADY_EXISTS`。
- 消费者为串行 worker（`app.reparse-enabled=true` 且角色含 `all`/`worker`）。
- 领取采用短事务 + 租约；解析在锁外执行。
- 租约过期（进程崩溃）：下一次领取把原尝试标为 `EXPIRED`（`LEASE_EXPIRED`），再新建尝试；最多 3 次，耗尽后任务 `FAILED`（`ATTEMPTS_EXHAUSTED`）。
- 每次尝试的结果不可变保存；任务 `result_json` 保存最后一次成功解析。

## 结果

`result_json` 形如：`{"event_type":"FIRE","battery_percent":"20","diagnostics_empty":true}`。`UNKNOWN` 是合法解析结论，不等于正常/恢复。

## API

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/management/messages/{id}/reparse` | `messages:reparse` | 创建任务（`{reason, actor_password}`，可带 `Idempotency-Key`） |
| GET | `/api/management/reparse-jobs?page=&size=&status=` | `messages:reparse` | 分页列表 |
| GET | `/api/management/reparse-jobs/{id}` | `messages:reparse` | 任务 + 全部尝试 |

## 界面

消息追踪详情新增「重解析（N07 · 无副作用）」区块：创建任务后轮询到终态，展示状态、尝试次数、解析结果与尝试轨迹。
