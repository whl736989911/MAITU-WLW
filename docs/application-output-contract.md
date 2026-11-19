# 开放应用与数据输出（`/management/applications`）

本契约对应 `docs/platform-delivery-plan.md` §6「开放应用 / 应用与数据输出」：应用、身份授权、透传/解析、转发启停及结果，与厂家来源账号分开建模。链路为生产端事实 → 事务内持久 outbox → HTTPS POST 或 MQTT TLS 投递 → 可审计结果。没有默认地址、默认密钥或默认应用；受控接收方验收与软件状态链验收分别记录。

## 1. 领域边界

| 对象 | 语义 | 不包含 |
|---|---|---|
| 应用 `output_applications` | 单位自有的出站应用（名称/说明/软归档） | 厂家来源账号、来源凭据、入站 appToken |
| 身份授权 `application_product_authorizations` | 已发布正式版本、旧产品解码器身份或厂商型号适配器身份，附设备范围及冻结制品摘要 | 未来型号自动扩展、跨单位授权、名称猜测身份 |
| 输出配置 `application_outputs` | 目标地址、认证方式、模式（归一化/原始单设备）、启停、超时 | 明文地址/凭据导出、任意主机 |
| 投递 outbox `application_delivery_outbox` | 每个（事实, 输出, 输出修订）一条持久捕获 | 时间窗口轮询历史数据的“转发器” |
| 尝试 `application_delivery_attempts` | 每次执行尝试的不可变结果 | 自动重发、伪造远端查询 |
| 变更历史 `output_application_events` | 应用/授权/输出/凭据的不可变变更记录 | 密钥或完整 URL |

旧库证据对照（`legacy-forensics-final-20261110`）：`ttl.iot_app`(a_id/a_name/a_key/a_is_penetrate) 与 `ttl.iot_app_forward`(af_url/af_param/af_status/af_fails/af_aid) 是「应用 + 转发」两表；旧转发 3s 超时、0 重试、失败只写日志且 `af_fails` 长期为 0。本实现保留“应用—输出”两层结构，但拒绝其失败即丢弃的行为：捕获、尝试与结果全部持久化，未确认状态显式保留。

`iot_app.a_key` 只用于旧平台**入站**通知 URL（`/api/notify/v1/<appToken>/...`），不是出站鉴权凭据；现有入站通道已由来源/接入令牌覆盖。因此本页**不**新增“应用查询令牌”接口——没有旧出站能力依赖它，增加即为无依据扩面。出站凭据只对应 `af_param` 一类语义，并以加密形式保存在输出配置上。

## 2. 生产端契约（父级接线）

```java
package com.fireplatform.integration;

public record NormalizedBusinessFact(
    String factId,              // 不可变唯一；同一事实重放必须相同（例如 "legacy-event:<legacy_ingest_events.id>"）
    String tenantId,            // 归属单位；未知则整条事实不投递
    String deviceId,            // 归属设备；未知/跨单位/仿真/已归档则整条事实不投递
    String productVersionId,    // 正式产品版本，可空；旧产品/厂商型号使用其原生权威身份
    String legacyProductId,     // 旧产品标识，可空，仅审计
    String sourcePlatform,      // 例如 lorawan_turbo / hikvision_firecloud
    String sourceProductKind,   // 来源实际证明的旧产品或厂商型号身份种类，可空
    String sourceProductId,     // 实际来源产品/型号标识，不从设备名称推断
    String sourceDriverKey,     // 厂商来源适配器标识，可空
    String sourceProductDigest, // 实际解码器/适配器制品的 SHA-256；未知时为空，不能以版本号代替
    String sourceMessageId,     // legacy_ingest_messages.id 或等价的原始消息 id
    String sourceEventType,     // kind / method，可空
    OffsetDateTime sourceEventTime, // 来源事件时间，可空
    OffsetDateTime receivedAt,  // 平台接收时间，必填
    String alarmId,             // 可空
    String normalizedJson,      // 已序列化一次的归一化 JSON 字符串；本域不重排、不改写
    String originalRawBase64,   // 原始报文字节（base64），可空
    String originalContentType, // 原始内容类型，可空（默认 application/octet-stream）
    boolean singleDeviceRaw,    // 生产端显式声明：原始字节只描述单一设备、单一单位
    boolean externalEffectsAllowed // 回放/补数必须为 false
);
```

```java
businessFactPublisher.publish(fact); // 必须在来源侧同一数据库事务内调用
```

约束与行为：

1. `publish` 使用 `@Transactional(propagation = MANDATORY)`：调用点必须有真实事务，outbox 行与来源事实同一提交。工作线程在提交后才外呼。
2. `externalEffectsAllowed = false` 时**不写任何行**：回放/补数（`reparse`、历史导入、镜像重放）绝不产生外部动作。
3. 归属未知（`tenantId`/`deviceId` 为空，或设备不属于该单位、已归档、为仿真设备）时跳过。正式版本缺失、旧解码器/厂商适配器摘要缺失等身份前置条件，可以形成捕获记录，但必须在认领时持久化为 `BLOCKED`，不能当作已接受；身份不符的事实不捕获到其他身份授权。
4. 捕获要求应用 `ACTIVE`、输出启用、有效身份与设备范围授权以及模式所需载荷。执行前重新核对冻结身份及摘要、应用/授权/设备归属与输出修订；撤销、归档、迁移和配置漂移均阻断，不继续发送。
5. `factId` 非法（空/超长）属于编程错误，会抛 `IllegalArgumentException`；这是唯一的失败方式。

### 2.1 模式载荷

| 模式 | 载荷来源 | 要求 |
|---|---|---|
| `NORMALIZED` | `normalizedJson` 的 UTF-8 字节，`Content-Type: application/json` | 生产端已序列化；本域不重新序列化 |
| `SINGLE_DEVICE_RAW` | `originalRawBase64` 解码后的原始字节 | `singleDeviceRaw = true` 且字节可用；内容类型受白名单校验 |

上限 1 MiB。原始模式**从不**把解析后的模型重新编码成 JSON 再当“原文”，也绝不把混合厂商回调（例如一次回调包含多个资源/单位）当成单设备报文。

### 2.2 接线示例（父级实现，非本文件改动）

`LegacyFactApplier.apply(...)` 已经持有 `eventId`、`messageId`、`event.deviceId()`、`device.tenantId()`、`event.platform()`、`observed`、`received`、以及每条事件的 `params`（归一化 JSON）与 `businessCode`。建议在同一事务内、事件插入之后：

```java
var version = jdbc.query("SELECT d.product_version_id, pv.legacy_product_id FROM devices d"
    + " LEFT JOIN product_versions pv ON pv.id=d.product_version_id WHERE d.id=?", ...);
String raw = jdbc.queryForObject("SELECT payload_raw FROM legacy_ingest_messages WHERE id=?", String.class, messageId);
boolean hik = "hikvision_firecloud".equalsIgnoreCase(event.platform());
publisher.publish(new NormalizedBusinessFact(
    "legacy-event:" + eventId, device.tenantId(), event.deviceId(), version.productVersionId(),
    version.legacyProductId(), event.platform(), messageId, storedKind, observed, received,
    /* alarmId：仅当本次事件创建/命中报警时 */ alarmId, params,
    raw == null ? null : Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)),
    "application/json",
    !hik,      // Hikvision 回调可能混合多资源/多单位：归一化输出仍可投递，原始模式必须为 false
    true));
```

- `factId` 必须与持久事件一一对应（`legacy-event:<legacy_ingest_events.id>` / `hik:<原始记录id>`），重复应用同一事件得到同一 `(factId, output, outputRevision)`，唯一约束使其幂等。
- 回放/补数（`reapply`、`reparse`、历史导入、镜像重放）必须传 `externalEffectsAllowed = false`。
- 归一化 JSON 与原始字节都只做编码拷贝，不做重新序列化；生产者不得在 `publish` 之后修改它们。
- 每次实际业务事件（而不是每条原始消息）发布一条事实；报警 ID 未知时传 `null`。

## 3. 执行与结果语义

1. 认领事务：重新校验应用/授权/设备归属/模式/冻结配置，然后**先提交 `IN_FLIGHT`**，再在事务外发起请求。崩溃只会留下 `IN_FLIGHT`，不会留下未记录的外部副作用。
2. 发送：JDK `HttpClient`，`followRedirects(NEVER)`，直连（禁用环境代理），连接超时 5s，请求超时取配置的 1000–30000ms，响应体最多保留 64 KiB。
3. 结果分类：

| HTTP/事件 | 状态 | 含义 |
|---|---|---|
| 2xx | `ACCEPTED` | 远端**接受 HTTP 请求**；不是“远端已业务处理” |
| 4xx | `REJECTED` | 远端明确拒绝 |
| 3xx | `UNKNOWN` | 不跟随跳转，结果未确认 |
| 5xx / 超时 / 网络错误 / 中断 | `UNKNOWN` | 请求可能已送达；**禁止自动重发** |
| 执行前校验失败 | `BLOCKED` | 未发起外呼（停用、撤销、归档、设备迁移、配置修订变化、私网解析等） |

4. 启动对账：`IN_FLIGHT` 超过 120s（大于任何允许的超时）的投递记为 `UNKNOWN / RECOVERY_INTERRUPTED`，并追加一条不可变尝试。**不做**远端查询、不做重发——接收方合同未知时不得伪造查询能力。
5. 每个 `(outbox_id, attempt_no)` 只写一次；结果更新带 `status = 'IN_FLIGHT'` 条件，避免并发覆盖。

## 4. 权限（需要根侧登记）

| 权限键 | 用途 | 受众 |
|---|---|---|
| `applications:read` | 查看应用、授权、输出配置摘要、投递结果 | 仅 `management` |
| `applications:write` | 新建/编辑/软归档应用，授权/撤销，输出配置与启停 | 仅 `management` |
| `applications:forward-raw` | 原始单设备转发能力；**必须同时具备 `messages:raw`** | 仅 `management` |

- 需要加入 `Limits.PLATFORM_PERMISSIONS`、`PermissionCatalog.label(...)`。
- 页面键 `applications` 需加入 `frontend/src/lib/types.ts` 的 `PageName`、`pagePermission`（`applications:read`），并在 `allowedPage` 中限定 `audience === 'management'`。
- `frontend/src/App.tsx` 需：`lazy` 导入 `@/pages/applications` 的 `ApplicationsPage`；新增导航项（建议「开放应用」分组，`page: 'applications'`，标题「应用与数据输出」）；在页面 `switch` 中加 `case 'applications'`。
- 权限授予本身由本迁移**不**执行：`V80` 不写入 `user_permissions`，避免隐式提权。部署方需显式授权；保护账号的权限补齐沿用 `V75` 的显式做法。

## 5. 部署配置（默认关闭）

| 键（环境变量） | 默认 | 说明 |
|---|---|---|
| `app.application-output-enabled` / `APP_APPLICATION_OUTPUT_ENABLED` | `false` | 交付 worker；仅 `worker`/`all` 角色生效 |
| `app.application-output-poll-ms` / `APP_APPLICATION_OUTPUT_POLL_MS` | `1000` | 轮询间隔（250–60000） |
| `app.application-output-batch-size` / `APP_APPLICATION_OUTPUT_BATCH_SIZE` | `5` | 单轮最多认领数（1–50） |
| `app.application-output-key` / `APP_APPLICATION_OUTPUT_KEY` | 无 | base64(32B)，AES-GCM 加密地址与凭据；缺失则拒绝保存/读取/发送 |
| `app.application-output-allowed-hosts` / `APP_APPLICATION_OUTPUT_ALLOWED_HOSTS` | 空 | 逗号分隔主机允许清单；为空则拒绝任何地址 |

- 地址只允许 `https` + 默认 443、无 userinfo、无片段、绝对路径；主机必须在允许清单内。
- 发送前再次解析主机，任一解析地址为回环/私网/链路本地/ULA/CGNAT/组播/保留地址即阻断（`ENDPOINT_PRIVATE_ADDRESS`）。
- 地址与凭据以 `application-output-url/v1|<tenant>|<output>`、`application-output-credential/v1|<tenant>|<output>` 为 AAD 加密；API 只返回可读源、掩码路径、SHA-256 摘要。

## 6. 导入/导出（`APPLICATION_OUTPUTS`）

- 导出列（配置清单）：`application_id, tenant_id, application_name, application_description, application_status, product_manufacturer, product_model, version_number, scope_mode, authorization_status, device_count, output_id, output_name, output_mode, output_enabled, endpoint_origin, endpoint_digest, note`。**不含** URL、凭据、载荷、投递行。
- 导入列：`application_name, application_description, product_manufacturer, product_model, version_number, scope_mode, device_euis, output_name, output_mode, note`。
- 每行创建：新应用 + 该已发布版本的授权 + **停用且无地址的草稿输出**。导入**不会**修改已有应用/输出（同名即行错误），也**不会**启用任何输出；地址与凭据必须由人工在页面上显式填写。
- 部分设备授权按 EUI 解析到当前单位真实设备，并逐个校验 `device:read` 范围与版本一致；全单位授权要求操作者当前 `ALL_TENANT`。
- 原始模式导入需要 `applications:forward-raw` + `messages:raw`。

## 7. 明确非目标

- 不保存、不迁移旧 `iot_app.a_key` / `iot_app_forward.af_param` 明文，也不自动导入旧转发行。
- 不提供“测试发送”“补发”“按时间窗重放”等无依据操作；`UNKNOWN` 只能人工核对远端后处置。
- 不把 `ACCEPTED` 呈现为“已送达/已处理”，不提供远端业务回执推断。
- 不向客户受众开放本页：应用输出是平台配置能力，客户可见范围由既有页面承担。

## 8. 停用草稿与当前验收边界

- HTTPS、MQTT 均允许先保存没有地址、主题或凭据的**停用草稿**。空草稿不需要加密密钥；提交任何敏感材料或启用时，服务端仍要求独立的 32 字节加密主密钥。前端不能反向要求空草稿填写被部署就绪条件隐藏的地址。
- 启用 HTTPS 仍须真实地址及所选认证方式要求的凭据；启用 MQTT 仍须 `mqtts://`、固定主题、用户名和密码。MQTT 固定 TLS 8883、短连接、clean session、QoS1；匹配 PUBACK 仅表示 broker 接受。允许清单、公网解析、TLS 主机校验及不自动重发的边界不因草稿放宽。
- `backend-tests-application-output01.json`：32 项通过、无跳过，包括 PostgreSQL 17 捕获保存点、三种授权身份及摘要漂移/撤销阻断、MQTT 配置和真实本地 TLS 对端 7 项传输检查。不是已向用户的外部系统完成投递。
- 私有实际页面完成应用创建、一个发布版本的一台设备快照授权、两种无材料停用草稿保存、缺材料启用拒绝、撤销授权、归档及六条历史保留；投递为零。客户端启用校验仍存在，绕过客户端后服务端独立返回 `APPLICATION_OUTPUT_ENCRYPTION_UNAVAILABLE`。
- 真实外呼仍需明确授权的接收地址或 broker、对应材料与允许主机；生产环境只读检查未发现应用输出主密钥或主机允许清单，worker 开关未设置。需在最终部署安全配置这些前置条件，不用占位地址或测试接收方代替用户目标。

证据位于 `artifacts/platform-completion-74cefca497/`：`application-draft-reproduction.json`、`application-draft-ui-local-acceptance.json`、`application-output-remote-environment.json`、`application-draft-ui-deployment-proof.json`。

