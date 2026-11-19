# 模块划分与连接规则（冻结 v1）

> 目的：让多个 agent 同时开发而互不踩踏。每个模块只暴露少量接口，只依赖接缝类型，不 import 别模块的实现类。
> 变更本文件由主线统一执行；模块内实现自由。

> **状态说明（2026-11-10）：**冻结 v1 的 A–I 接缝、V22 legacy-ingest HTTP/API 与自测命令属于历史方案，不是当前真实设备或海康接入指南，不用于启动旧模拟工具。当前入口和限制见下节。

## 当前能力与边界（2026-11-12）

- 前端管理/客户业务入口位于 `frontend/src/pages/`；设备、告警、故障、监测、历史等页面依现有路由及权限显示。页面存在不表示每个来源/外部渠道均已接通。
- 管理端读取/处理仍受既有管理权限和显式单位选择约束；客户端只能读取会话授权的本地单位数据。来源侧单位不能替代本地归属或授权。未明确归属的海康设备不得自动放入客户单位。
- 海康已有设备、故障、监测与原生事件取数入口；接口状态不等于在线，收包时间不等于心跳。V66/V67 在显式启用时接收 980002 报警、980004 故障、980008 监测及 980016 设备事件，通过原文存档、队列、统一去重和原归属处理。历史 H2/PG17 验收不代表真实厂商投递、签名或重试已验证；云订阅核对只读。详见[后端 API 边界](../backend/README.md#api与业务边界)。
- 已有设备来源登记、本地验收、设备服务、参数及命令回执入口；后端以真实驱动、来源关联、型号能力证据和独立权限控制可用操作。厂商受理、通道受理和终端执行分别记录，缺失回执不能标成执行成功；入口存在不表示对应厂家或硬件已完成真实联调。旧模拟适配器、模拟指令和模拟成功结果已退役；历史命令/审计数据不能作为当前真实执行证据。SIM卡台账是独立业务，不是模拟设备。
- 仅可把当前实现与当前独立证据作为验收依据；本表旧协议形状和历史测试命令不得当作现行接口或部署步骤。

## 当前使用导航

- 管理端 `frontend/src/App.tsx` 当前分组：总览、设备与接入、事件与处置、组织与权限、通知与费用、系统设置；核心入口常显，点击分组展开辅助页，菜单按权限过滤。客户端为首页、设备、报警、我的。
- 组织与权限维护单位/位置、用户、角色及设备范围。管理端左侧单位/位置树仅过滤读取；新增或移动必须显式选择真实目标，既有记录按实际归属操作。
- 设备资产页支持手工新增、编辑、移动与保留历史归档；批量导入提供旧烟感本地新建和海康已发现设备关联两套模板，后者不做云端批量开户。归档/重建不代表来源恢复或真实设备激活。
- 事件与处置承载报警、故障及人工处置；通知与费用维护联系人、规则、通道和独立短信/语音资源。具体外部结果、命令和授权边界分别见[通知合同](aliyun-notification.md)与[设备命令合同](device-command-contract.md)。

以上是入口索引，不是部署或实机验收结论；发布及验证事实以对应部署证据为准。

## 1. 模块表

| 模块 | 代码目录 | 对外暴露 | 允许依赖 | 自测命令（在沙箱内） |
|---|---|---|---|---|
| **A 接入** | `legacyingest/ingest/**`、`legacyingest/web/**` | `LegacyIngestService.ingest(ingress, platform, messageType, rawBody)` | 接缝类型、JdbcTemplate、`LegacyDecodeService`（接口调用） | `mvn -o -B -ntp -Dtest='LegacyIngest*Test' test` |
| **B 解码** | `legacyingest/codec/**`、`scripts/codec-sandbox/**` | `LegacyDecodeService.decode(DeviceBinding, vendorDeviceId, payloadHex)`、`LegacyCodecRegistry.findLatest` | 无（进程边界） | `mvn ... -Dtest=CodecSandboxTest test` |
| **C 事实** | `legacyingest/facts/**` | 实现 `LegacyEventSink`；`DeviceStatusComposer.compose`；`OfflineEventTask.scan()` | 接缝类型、JdbcTemplate | `mvn ... -Dtest='LegacyFactApplyTest,OfflineEventTaskTest' test` |
| **D 导入** | `legacyingest/importer/**` | `importDictionaries()`、`importPilotDevices()` | 表结构（V22） | `mvn ... -Dtest=LegacyDictionaryImportTest test` |
| **E 查询** | `legacyingest/query/**` | 4 个 `GET /api/management/legacy-ingest/**` | 表结构（V22） | `mvn ... -Dtest='LegacyQuer*Test' test` |
| **F 导出** | `legacyingest/export/**` | `POST /api/management/legacy-ingest/exports` | 表结构 | `mvn ... -Dtest='LegacyIngestExport*Test' test` |
| **G MQTT** | `legacyingest/mqtt/**` | `LegacyMqttTopicParser.parse(topic, payload)`；订阅器 bean | 调 `LegacyIngestService`（接口） | `mvn ... -Dtest='LegacyMqtt*Test' test` |
| **H 前端** | `frontend/src/pages/legacy/**`、`frontend/src/lib/legacy-*.ts` | 无（消费 HTTP 契约） | HTTP 契约（§3） | `npm test -- legacy` |
| **I 运维/性能** | `scripts/ops/**`、`scripts/perf/**`、`docs/ops-runbook.md`、`docs/perf-baseline.md` | 无 | 无 | `bash -n <脚本>` |

## 2. 接缝类型（唯一允许跨模块引用的类型）

```java
// com.fireplatform.legacyingest
record LegacyNormalizedEvent(String tenantId, String deviceId, String vendorDeviceId, String platform,
                             String kind, OffsetDateTime observedAt, BigDecimal battery,
                             boolean pendingDecoder, List<LegacyEvent> events)
interface LegacyEventSink { void accept(String messageId, LegacyNormalizedEvent event); }
// com.fireplatform.legacyingest.codec
LegacyDecodeService.decode(DeviceBinding binding, String vendorDeviceId, String payloadHex) -> LegacyNormalizedEvent
LegacyDecodeService.DeviceBinding(String tenantId, String deviceId, String platform, String legacyProductId, Map<String,Object> attrs)
```

**规则**
1. 除以上 3 个类型外，**禁止** import 其他模块的具体类。违反即视为越界。
2. 平台字符串（`aep_turbo`、`lorawan_turbo`、`tyy_turbo`、`turmass`、`maitu_mqtt`、`formttl`）由**接入模块**统一注册；其他模块只传字符串，不判断具体类。
3. 表结构由迁移 `V22` 冻结。任何模块需要新列/新表，先向主线提出，由主线改迁移并通知全部模块。
4. 每个模块的 Spring Bean 构造器若存在多个，必须给注入用的那个加 `@Autowired`（已因该问题失败三次）。
5. 模块之间只通过上述接口与数据库表通信，**不共享静态状态**。

## 3. HTTP 契约（前端与后端之间）

| 方法 | 路径 | 响应形状（snake_case） |
|---|---|---|
| POST | `/api/notify/v1/{appToken}/{platform}/telemetry` | `{id, state, duplicate, events, failure_code}` |
| POST | `/v1/formttl/{messageType}/telemetry` | 同上（令牌走 `X-Legacy-Token`） |
| GET | `/api/management/legacy-ingest/messages` | `Page{items:[{id,ingress,vendor_platform,vendor_device_id,device_id,received_at,observed_at,state,failure_code,duplicate_count,event_count}],total,page,size}` |
| GET | `/api/management/legacy-ingest/messages/{id}` | 同上 + `payload_raw` + `events[]` |
| GET | `/api/management/legacy-ingest/reconcile` | `Page{items:[{id,run_id,kind,legacy_id,new_id,detail,created_at}]}` |
| GET | `/api/management/legacy-ingest/devices` | `Page{items:[{id,name,device_eui,model,tenant_id,place_name,legacy_product_id,physical_state,last_message_at}]}` |
| POST | `/api/management/legacy-ingest/import-dictionaries` | `{tenants,places,products,versions,attributes,decoders,differences}` |
| POST | `/api/management/legacy-ingest/import-pilot-devices` | `{devices,aliases,differences}` |
| POST | `/api/management/legacy-ingest/exports` | `text/csv`（UTF-8 BOM） |

权限统一 `legacy-ingest:read` / `legacy-ingest:write`，管理端 + 必须带 `X-Tenant-Id`。

## 4. 互不干扰的验证方式（关键）

共享 `backend/target` 会让并发 maven 互相覆盖。因此每个模块在自己的**沙箱副本**里跑测试：

```bash
# 建沙箱（一次）
scripts/dev/sandbox.sh init m1

# 在沙箱里改代码、跑自己的测试（不影响主树，也不影响别人）
scripts/dev/sandbox.sh test m1 "-Dtest=LegacyDictionaryImportTest"
```

- 沙箱位于 `/tmp/fire-sandbox/<module>`，独立 `target/`，共享只读的 `~/.m2`。
- 模块自测通过后，把**自己目录内的文件**拷回主树（脚本提供 `sync-back`）。
- 主线只在主树做**集成验证**（全量 legacy 套件 + 全量后端套件 + 前端构建）。

## 5. 主线职责（不外包）

- 迁移 `V22`、`RequestSecurity` 放行、`application.yml`、`FirePlatformApplication`
- 接缝类型（§2）
- 沙箱与构建脚本
- 集成验证与交付报告

## 6. 历史冻结 v1 缺口（不作为当前未完成清单）

| 缺口 | 影响 | 归属 |
|---|---|---|
| `legacy_ingest_events` 无 fault 专表 | 故障只记事件、不进独立故障台账 | C（等主线定表） |
| `alarms` 无结束状态列 | RECOVERY 只能靠事件与时间窗推断 | C |
| `codec-timeout-ms` 配置未被 runner 使用 | 超时固定 1s | B |
| `d_product_id` 未导出 | 设备→产品只能按型号映射，4 个型号有歧义 | 数据取证 |
