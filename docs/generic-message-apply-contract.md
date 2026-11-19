# 真实通用消息捕获与自动应用合同（当前代码边界）

> 本页现行说明取代下方旧版 N06 手工/模拟应用草案。旧 V23 草案及其模拟应用表述不再是操作指南；不得据此创建模拟源、模拟设备或调用旧模板/模拟执行接口。当前生产部署版本和验收结果须以父级补充的部署证据为准，本页不表示 V58 已部署或本轮验收通过。

## 捕获与资格

`POST /api/ingest/{source}/messages` 仅接受经来源凭据认证的通用报文；服务保存不可变原文、来源捕获时间和接收回执，不提供模拟业务种子。只有捕获时已绑定的真实设备、`capture_provenance='AUTHENTICATED_SOURCE'`、仍有效的来源及绑定关系、且设备不是历史模拟设备，才可应用。`LEGACY_UNVERIFIED` 历史记录不得应用成真实事实。

`sequence` 缺省保持 null，不推断；`occurredAt` 必须为带时区 ISO-8601。源时间缺失或无效的报文不得应用。不能因来源状态码、轮询接收时间或设备最近在线推断火警或设备在线。

## 自动应用

`GenericMessageApplyWorker` 在 `all` 或 `worker` 角色、且通用接入启用时自动运行；`api`、`ingest` 单独角色不执行该 worker。按 `received_at,id` 每轮最多选取20条满足认证来源、捕获时已绑定且 `application_state='NOT_APPLIED'` 的记录；共用 `app.task-poll-ms` 轮询间隔（默认500ms）。worker 使用独立自动应用入口，不要求人工账号、密码、Idempotency-Key 或人工申请任务。

自动应用会重新检查来源启用状态、绑定、设备及版本，并使用捕获时报文的产品版本归一化。只接受 `FIRE`、`RECOVERY`；成功后在同一事务更新物理事实/报警（仅 FIRE 新建报警）、设备最后消息时间及水位，并写 `MESSAGE_APPLIED` outbox 与审计。失败的瞬态异常由后续轮询再尝试；持久拒绝记为 `REJECTED` 并写对应 outbox/审计，不把未应用/拒绝历史伪装成成功。

水位按设备保存 sequence 与源事件时间：sequence 仅在双方都有值时按 `<=` 判断旧序；有效 `occurredAt` 不晚于水位时间时也判为 `STALE`。任一项满足即不写设备/报警事实、不推进水位，并记录 `MESSAGE_STALE`。无效/缺失源时间始终拒绝，不以服务接收时间替代。

## 保留的人工 API 与预览边界

管理端仍有原始消息读取、只读预览和显式 apply API：

- `GET /api/management/messages`、`GET /api/management/messages/{id}`：消息读取权限 `messages:read`。
- `POST /api/management/messages/{id}/preview`：`messages:preview`，snake_case请求含 `expected_revision`、`reason`、`actor_password`；仅生成预览，不改变设备、报警、水位或应用状态。
- `POST /api/management/messages/{id}/apply`：`messages:apply`，snake_case请求含 `expected_revision`、`reason`、`actor_password`，可带 `Idempotency-Key`。适用于合格真实捕获的显式人工应用，不绕过认证来源、源时间、捕获时绑定及实时授权条件。

手动 apply 对旧/模拟/非认证捕获返回冲突，不会通过把来源改标为真实来绕过条件。重复请求按原 key 回放其原回执；已终态记录返回当前状态而不重复写 outbox。

## 历史模型说明

`business_status` 等旧字段及旧审计/原始捕获保留为历史兼容资料，不能解释成当前真实应用成功。`message_outbox` 是本地事务 outbox，不是外部投递器；本合同不提供外部消息副作用。历史 V23 N06 文本中关于模拟来源、模拟设备和显式手工模拟 apply 的内容已退役，不按其中 API 示例操作。
