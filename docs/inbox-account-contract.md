# N13：客户站内消息与个人中心（Frozen v1，实施合同）

## 目标与边界

客户/平台用户各自的站内消息与个人中心。与管理端「消息追踪」（原始报文）完全分开。

- 站内消息按**用户**独立保存与标状态；`已读` ≠ `已处理`。
- 已处理不可回退；重复处理幂等返回当前状态（不改 revision）。
- 个人中心只允许本人操作：改名、查看会话、撤销其它会话；不列出他人数据。
- 不实现邮件/短信/推送通道，不把管理报文追踪当客户消息。

## 数据（V29）

`user_messages`：`tenant_id`、`user_id`、`kind`（`SYSTEM`/`ALARM`）、`title`、`body`、`status`（`UNREAD`/`READ`/`HANDLED`）、`created_by`、`created_at`、`read_at`、`handled_at`、`revision`。

`sessions` 增加 `created_at`（新会话写入；旧行为 NULL 显示"未记录"）。

## API

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/me` | `inbox:read` | 本人资料 |
| PUT | `/api/me/profile` | `inbox:read` | 改姓名（1..200 字） |
| GET | `/api/me/sessions` | `inbox:read` | 本人有效会话（含 `current` 标记） |
| POST | `/api/me/sessions/revoke-others` | `inbox:read` | 撤销除当前外的全部会话 |
| GET | `/api/me/messages?page=&size=&status=` | `inbox:read` | 本人消息 |
| POST | `/api/me/messages/{id}/read` | `inbox:read` | CAS `expected_revision` |
| POST | `/api/me/messages/{id}/handle` | `inbox:handle` | CAS；已处理幂等 |
| POST | `/api/management/user-messages/publish` | `inbox:publish` | 向单位内启用的客户账号投递 |

发布按单位内**启用的 CUSTOMER 账号**逐个生成独立消息；返回投递份数。

## 权限

- 平台权限新增 `inbox:read`、`inbox:handle`、`inbox:publish`。
- 客户默认权限新增 `inbox:read`、`inbox:handle`。

## 界面

新增「个人中心」页（客户与平台可见）：资料编辑、会话列表与撤销、站内消息（标记已读/已处理）。
