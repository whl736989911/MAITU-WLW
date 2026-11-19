# N14：值守概览与实时追赶（Frozen v1，实施合同）

## 目标与边界

给管理端一个"当前观测"的值守视图，并把补拉做成**显式动作**。

- 概览只读：不写业务、不发通知、不改报警。
- head 与计数只代表**当前观测**，不构成"无漏报"保证；断线期间不推断已补齐。
- 补拉复用既有 `alarm-changes` 游标协议；`has_more`/`pending_count` 由服务端给出。
- 浏览器声音/通知授权失败必须**可见**，不静默降级。

## API

`GET /api/management/ops-overview`（权限 `summary:read`，管理端；可用 `X-Tenant-Id` 限定单位，省略时仍受当前授权约束）

```json
{
  "computed_at": "...",
  "alarms": { "unhandled": 1, "fire": 1, "oldest_unhandled_at": "..." },
  "channel": { "head": 7, "pending": 0, "last_recorded_at": "..." },
  "tasks": { "processing_pending": 0, "processing_running": 0, "reparse_pending": 0, "reparse_running": 0 },
  "sources": { "enabled": 1, "last_received_at": "..." },
  "semantics": "head与计数只是当前观测，不构成无漏报保证；…"
}
```

- 设备归属的报警、变更通知、处理任务和重解析任务统计，同时按单位与当前用户的设备范围隔离；不能只按单位汇总。无可见可发布记录时 `head` 为 `null`，计数为 0。
- 来源是单位级资源，不能推断为某个设备所有；来源统计只包含当前用户具有完整设备可见范围的单位。受限单位未计入来源数，不代表该单位实际没有来源。
- 只读接口，无审计写入、无事件发布。

## 补拉

前端"立即补拉"调用既有 `GET /api/{audience}/alarm-changes`：

- 无游标时为 `BASELINE`（head 基线，不返回历史条目）；
- 有游标时按服务端序返回并给出 `next_cursor` / `has_more` / `pending_count`；
- 需要本机启用 `app.alarm-change-read-enabled`；未启用时接口 503 `ALARM_CHANGES_DISABLED`，界面必须显示失败原因。

## 界面

运行总览新增「值守概览」卡片：未处理/火警数、最久未处理、通知发布 head/待发、处理队列、来源启用与最近接收、语义声明；并提供"立即补拉"与"测试声音授权"两个动作，结果都以 `role=status` 可见。
