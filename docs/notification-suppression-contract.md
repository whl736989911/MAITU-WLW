# N08：故障处置与通知屏蔽（Frozen v1，实施合同）

## 目标与边界

对通知派发施加**有期限**的屏蔽。屏蔽只抑制通知，不改设备连接、物理状态或人工处置状态，也不改报警事实。

- 必须有明确期限（≤90 天）与原因；到期自动失效（读时推导，不依赖后台扫描）。
- 可随时取消，保留取消人与原因；历史记录不删除。
- 火警默认**穿透**屏蔽；只有创建时显式勾选"同时屏蔽火警通知"（二次确认）才一并抑制。
- 不引入领取/审批流；不改真实厂商、短信语音或硬件链路。

## 数据模型（V25）

`device_suppressions`：`kind=NOTIFICATION`、`scope`（`TENANT` 全部设备 / `DEVICES` 指定设备集）、`fire_confirmed`、`reason`、`starts_at`、`ends_at`（`CHECK(ends_at > starts_at)`）、`status`（`ACTIVE`/`CANCELLED`）、`created_by`、`cancelled_at/cancelled_by/cancel_reason`、`revision`。

`device_suppression_members`：`DEVICES` 范围的成员，`(device_id, tenant_id)` 外键保证同单位。

## 生效判定

生效 = `status='ACTIVE'` 且 `starts_at <= now < ends_at` 且
- `scope='TENANT'`，或该设备在成员表中；
- 且（事件非火警，或 `fire_confirmed=TRUE`）。

## 派发拦截

| 路径 | 行为 |
|---|---|
| 人工模拟派发 `POST /api/management/alarms/{id}/notifications/simulate` | 命中屏蔽 → 409 `NOTIFICATION_SUPPRESSED`，不创建任何记录 |
| 自动模拟策略 `AutomationPlanner` | 命中屏蔽 → 意图置 `SKIPPED`，错误码 `NOTIFICATION_SUPPRESSED`，不建 dispatch |

## API

| 方法 | 路径 | 权限 |
|---|---|---|
| GET | `/api/management/suppressions?page=&size=&status=` | `suppressions:read` |
| GET | `/api/management/suppressions/{id}` | `suppressions:read` |
| POST | `/api/management/suppressions` | `suppressions:write`（密码二次确认） |
| POST | `/api/management/suppressions/{id}/cancel` | `suppressions:write`（密码二次确认，CAS `expected_revision`） |

## 界面

新增「通知屏蔽」页（受控模拟通知分组）：列表、创建（范围/设备列表/火警二次确认/到期时间/原因）、取消；两处写操作都走密码二次确认。
