# 批量导入、导出与报表合同

## 当前通用数据交换

当前页面使用 `/api/management/data-transfer`、`/api/customer/data-transfer`，能力与列定义从 `/kinds`、`/template` 获取。使用 CSV（UTF-8）或 XLSX 文件控件，先预览，再按源文件摘要、用途、当前密码和幂等键明确确认；每行独立执行正式领域命令，成功行不因其他行失败而回滚。任务及逐行结果、错误报告、导出快照和下载摘要分别持久化。

- 文件上限 2,000,000 字节、10,000 行；标识符、号码使用文本单元格，不允许公式或宏。导入、导出除数据交换权限外，还要求对应业务类型权限；客户端固定当前登录单位，不因文件内容扩大设备或单位权限。
- 联系人预览与手工维护复用同一名称、联系人类型及电话号码校验。支持明确格式的中国手机号和带区号分隔的座机；非法号码、类型、非整数或负数更新版本在预览阶段拒绝。客户更新联系人时空号码表示保留原号码；创建必须填写号码，写入仍重验当前对象、版本和权限。
- 管理端 `CONTACTS` 与客户 `UNIT_CONTACTS` 分别使用各自模板和权限，不能交换端身份绕过授权。客户联系人导出保留 ID、版本及资料，号码列为空，不导出明文号码；按原 ID、版本导回时可保留原号码及绑定。
- `RULE_DRAFTS`、`UNIT_NOTIFICATION_RULES` 使用下列八列模板。短信、语音、公众号的升级间隔分别读取、校验、写入和导出，不再丢失公众号多级间隔。请重新下载当前模板，不手工沿用旧七列表头。

```csv
id,name,event_scope,receivers_json,sms_escalation_interval_minutes,voice_escalation_interval_minutes,wechat_escalation_interval_minutes,enabled
```

规则导入只新建停用草稿，不能通过文件启用或覆盖原规则；启用需在规则维护页另行明确操作。导出 ID 是选择依据，不把再次导入变成更新原记录。收件人必须属于本单位且已启用；公众号仍要求有效的当前公众号绑定，多级间隔为 0—60 分钟。导入或导出不构成真实发送、实际送达、OAuth、支付或设备控制验收。

当前验收记录见 [逐项交付记录](platform-delivery-plan.md)：真实私有文件控件完成联系人与故障规则各 1 行成功、1 行预览拒绝，下载 CSV/XLSX 模板、错误报告及导出；9 项数据交换集成场景包含两个公众号级别和独立间隔导出再导入。生产不导入验收夹具。

## 故障处置状态筛选导出（2026-11-14）

- `FAULTS.filters.handling_state` 支持 `UNRECORDED,UNHANDLED,HANDLED`，空值或省略不限制本地处置状态，非法值拒绝。与既有设备、单位/位置、关键词及来源时间条件取交集，仍须原有业务和导出/下载权限。
- 全部匹配导出复用列表的状态 SQL 过滤，在计数和分页前执行；勾选导出精确读取并重新核对每条状态，选中不匹配记录时拒绝，不静默扩大筛选。导出 `handled` 列继续保留空白、`false`、`true` 三态。
- 设备详情只传已提交筛选；修改下拉草稿不会改变翻页或导出。实际 `UNRECORDED` CSV 12 条均无处置记录且 `handled` 为空；明确提交 `HANDLED` 后 XLSX 0 条。事件、设备、状态、表头和 SHA-256 与 API/快照一致。
- 仅创建 2 个正常导出快照及既有元数据，没有为验收写入真实处置记录。明确 `false/true` 的正向数据、勾选状态不匹配拒绝及状态/时间交集由隔离回归覆盖，真实客户账号与实际 UI 下一页未冒称验收。
- 证据：[定向验证](../artifacts/platform-completion-74cefca497/fault-handling-filter-focused-verification.json)、[真实页面](../artifacts/platform-completion-74cefca497/fault-handling-filter-production-browser.json)、[文件核验](../artifacts/platform-completion-74cefca497/fault-handling-filter-file-verification.json)。

## 故障导出处置三态（2026-11-14）

`FAULTS` 的 `handled` 列保持原列名和位置，完整导出与勾选导出采用相同语义：

| 单元格 | 对应事实 |
|---|---|
| 空白 | 尚无本地处置记录，不能推定未处置 |
| `false` | 已有处置记录，明确为未处置 |
| `true` | 已有处置记录，明确为已处置 |

不新增或回填处置记录，不改变来源故障/恢复事实、设备状态、筛选或授权范围。已生成的历史快照不重写；旧文件无法据 `false` 还原是否曾有处置记录，应重新导出并核对当前记录。

12 项相关后端回归通过，包括三种持久化状态分别经完整与勾选路径导出。真实故障页面完成完整 CSV 12 条与勾选 XLSX 2 条下载，全部缺少处置记录的 `handled` 单元格均为空；文件标识、实际勾选范围及 SHA-256 与 API/快照一致。未为验收写入真实处置记录；真实明确 `false/true` 状态由隔离回归验证，未冒称生产处置操作验收。

证据：[相关回归](../artifacts/platform-completion-74cefca497/fault-handling-export-focused-verification.json)、[真实完整与勾选操作](../artifacts/platform-completion-74cefca497/fault-handling-export-production-browser.json)、[文件内容](../artifacts/platform-completion-74cefca497/fault-handling-export-file-verification.json)。

## 本设备故障来源时间筛选导出（2026-11-14）

- 设备详情 `FAULTS` 导出固定 `unit_ids,device_id`，`from,to` 使用已提交的明确 `+08:00` 时间，未提交或仅清空输入框不改导出条件；明确“清空故障时间条件”才移除查询/导出时间范围。
- 导出复用设备故障查询，设备及权限、来源观察时间条件在 SQL 计数和分页前应用，包含全部匹配记录，继续按既有 10,000 行上限拒绝超限结果。无设备过滤的全局导出及勾选记录行为保持不变。
- 未知观察时间在无时间范围时保留，在有任一来源时间边界时排除；不以接收时间或记录时间代替。实际 CSV 0 行与有界空结果一致，清空后 XLSX 12 行与本设备真实故障一致，文件摘要匹配快照回执。只创建 2 个正常导出快照及既有元数据。
- 证据：[相关回归](../artifacts/platform-completion-74cefca497/device-fault-filter-focused-verification.json)、[真实页面与已应用条件](../artifacts/platform-completion-74cefca497/device-fault-filter-production-browser.json)、[实际文件核验](../artifacts/platform-completion-74cefca497/device-fault-filter-file-verification.json)。

## 本设备关联报警导出（2026-11-14）

- `ALARMS.filters` 支持 `device_id`，与当前单位、单位/位置树及设备授权取交集，在 SQL 限量之前应用。设备详情固定 `unit_ids,device_id`，沿用已提交的 `keyword,physical_state,response_state,conclusion,occurred_from,occurred_to`；不传列表页码，不按名称推定关联。
- `UNREVIEWED` 明确匹配 `conclusion IS NULL`，具体复核结论仍按原值匹配。来源时间使用 `[occurred_from,occurred_to)`；`source_time_known=FALSE` 时发生时间导出为空，不能用平台接收或记录时间填补，有来源时间条件时排除此类记录。
- 生成继续要求 `exports:write`、`alarm:read`、用途、当前密码和幂等键；下载重新校验 `exports:read` 和授权范围。CSV/XLSX、10,000 行限制、加密快照及摘要核验均复用现有流程，不新建导出接口。
- 真实管理页面下载 CSV/XLSX 各 1 条，报警 ID、设备、物理/人工状态、空结论、未知发生时间及 SHA-256 与 API/快照一致；未提交的误报草稿不影响未复核导出。只创建 2 个正常导出快照及既有审计/幂等元数据，没有模拟报警、设备写入、硬件或收费操作。
- 19 项独立后端相关场景、25 项前端设备详情回归及生产构建最终通过；跨页 101 条、其它设备及授权排除、字面关键字、微秒边界和未知来源时间由隔离回归验证。实际所选设备仅 1 条报警，未冒称真实点击下一页或真实客户浏览器验收。
- 证据：[定向验证](../artifacts/platform-completion-74cefca497/device-alarm-export-focused-verification.json)、[真实页面](../artifacts/platform-completion-74cefca497/device-alarm-export-production-browser.json)、[实际文件核验](../artifacts/platform-completion-74cefca497/device-alarm-export-file-verification.json)。

## 本设备事件筛选导出（2026-11-14）

- 设备详情“事件记录”复用 `EVENTS` 导出，固定记录所属 `unit_ids`、`device_id`；已应用的页面 `kind,from,to` 分别映射为导出 `kind,occurred_from,occurred_to`，时间显式 `+08:00`。未提交草稿不改变导出，生成/下载权限及密码确认流程不变。
- 查询与导出共用统一事件投影，类型与来源观察时间条件均在 SQL 计数/限量前执行；未知来源时间不使用接收时间补齐。导出所有匹配事件，不限当前页，继续沿用 10,000 行上限，不把一条多指标上报拆成多个事件行。
- 真实海康设备按类型和来源时间匹配 2 条事件，实际下载 CSV 2 行；事件标识、设备、类型、方法、状态、原生事件字段及上报/接收时间与 API 一致，文件摘要同时匹配下载与快照回执。未提交的 `OTHER` 类型草稿没有替换已应用的 `PARAM` 导出。
- 本轮只新增 1 个正常导出快照及其既有审计/幂等元数据，不导入或创建模拟事件。证据：[真实查询与下载](../artifacts/platform-completion-74cefca497/device-event-filter-production-browser.json)、[实际文件内容](../artifacts/platform-completion-74cefca497/device-event-filter-file-verification.json)。

## 本设备监测历史导出（2026-11-14）

- 双端设备详情“监测数据 → 历史记录”复用通用 `MONITORING` 快照及 CSV/XLSX 下载流程，不新增另一套文件接口。生成仍须 `exports:write`、`device:read`、用途、当前密码和幂等键；下载重新核验 `exports:read` 及原快照的有效范围。
- `MONITORING.filters` 新增 `device_id` 和 `history_only`。设备限制与单位、位置、设备授权取交集，在 SQL 计数及分页之前执行；`history_only` 只接受 `true/false`，为 true 时不合并当前轮询快照。未提供新筛选的原全局导出行为保持不变。
- 本设备页面固定传入记录所属 `unit_ids`、`device_id`、`history_only=true`，时间使用已提交的 `observed_from/observed_to` 和明确 `+08:00`。未提交的日期草稿不改变导出；导出全部匹配历史，不限列表当前页，超过既有 10,000 行限制时明确拒绝，不静默截断。
- 一条上报可含多个指标，文件按指标逐行导出；文件行数不是上报条数。未知来源时间、接收时间和单位保持空值，不用当前快照、入库时间或猜测单位补齐。CSV 公式前缀防护和 XLSX 文本单元格沿用既有规则。
- 真实页面已生成并实际下载：时间筛选内 2 条上报对应 CSV 5 行，全部 8 条历史对应 XLSX 11 行。逐项核对设备、指标、精确值、单位和来源时间，并核对实际下载字节与快照/文件 SHA-256；日期草稿改变后导出仍使用已提交条件。
- 本轮只创建 2 个正常导出快照及其既有审计/幂等元数据，不修改设备、报警或归属。21 项后端、27 项前端相关测试通过，实际管理端桌面及 390px 导出抽屉已检查；不声称已用真实客户账号完成浏览器验收。
- 证据：[相关回归](../artifacts/platform-completion-74cefca497/device-history-export-focused-verification.json)、[真实页面与下载](../artifacts/platform-completion-74cefca497/device-history-export-production-browser.json)、[实际文件内容核验](../artifacts/platform-completion-74cefca497/device-history-export-file-verification.json)。

当前设备和事件导出统一走 `data-transfer/exports` 及其下载入口；历史 `/api/management/exports` 入口已退役，旧快照不改写。按位置导出事件时，以本次授权范围内设备的当前归属位置筛选，不从历史事件猜测位置。下文 N12 原批量确认接口保留拒绝记录；只有已确认成功的源哈希返回原任务，修复校验问题后可重新确认相同文件。

## N12 历史设备批量接口验收（2026-11-05）

以下保留当时独立设备批量接口的历史语义和验收，不适用于当前通用数据交换页面；旧表头、整批成败和旧容量限制不能用于解释当前通用任务。

### 目标与边界

设备批量导入与业务清单导出。两者都不接触真实厂商数据。

- 导入：整批校验、**逐行错误**、**整批成败**（任一行失败则整批不写入），同一源内容幂等重放。
- 导出：生成不可变快照（内容 + SHA-256），按权限下载；跨租户不可见。
- 一次导入最多 200 行、CSV ≤1 MiB；不实现异步大文件任务或对象存储。

2026-11-05：设备导入切换为六列 CSV，末列 `place_id` 必填。旧五列表头明确拒绝，不再通过导入创建无位置设备。

### 导入

CSV 表头固定：

```csv
name,device_eui,address,product_version_id,is_simulated,place_id
```

校验项与错误码：

| 错误码 | 条件 |
|---|---|
| `COLUMN_COUNT` | 列数不为 6 |
| `NAME_INVALID` / `ADDRESS_INVALID` | 空或超长 |
| `DEVICE_EUI_INVALID` | 不符合 `[A-Za-z0-9][A-Za-z0-9._:-]{0,63}` |
| `DUPLICATE_IN_BATCH` | 批内重复 EUI |
| `DEVICE_EUI_EXISTS` | 本单位已存在 |
| `VERSION_NOT_PUBLISHED` | 版本不存在或未发布 |
| `SIMULATED_FLAG` | 非 true/false |
| `PLACE_REQUIRED` | 去除首尾空白后位置 ID 为空 |
| `PLACE_NOT_FOUND` | 位置 ID 超过 64 字符、不存在或不属于当前单位；不区分不存在与跨单位 |

接口：

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/management/devices/import/preview` | `imports:write` | 只校验，返回逐行结果与 `source_sha256` |
| POST | `/api/management/devices/import/confirm` | `imports:write` | 需 `expected_sha256`；有错误 → 409 `IMPORT_HAS_ERRORS`（任务记 `REJECTED` 与逐行原因）|
| GET | `/api/management/devices/imports/{id}` | `imports:read` | 任务摘要 |
| GET | `/api/management/devices/imports/{id}/rows` | `imports:read` | 逐行结果 |

幂等：同一 `source_sha256` 的确认请求直接返回既有任务，不重复写入。

- 预览结果及任务逐行结果包含 `place_id`，合法导入将该值写入 `devices.place_id`。
- 确认时重新校验整批内容与位置；预览后位置被删除时，整批拒绝，不先写入合法行。
- V41 只为 `device_import_rows` 新增可空 `place_id` 快照列；历史记录保持 NULL，不能用设备现在的位置补造过去。记录不绑定位置外键，设备移动或位置删除不能改写导入历史。
- 历史任务仍可读取；旧五列源内容不能重新提交。新六列源内容继续按源哈希幂等。
- 不收紧 `devices.place_id` 的数据库约束，不补造历史设备位置。单设备开户亦已要求当前单位的既有位置，详见 [正式设备合同](product-contract.md#正式设备)；批量导入不能绕过开户服务的位置校验。

### 导出

| 方法 | 路径 | 权限 |
|---|---|---|
| POST | `/api/management/exports` | `exports:write` |
| GET | `/api/management/exports/{id}/download` | `exports:read` |

- `kind=DEVICES`（v1）；内容为 CSV（`id,name,device_eui,address,product_version_id,is_simulated,physical_state,last_message_at,revision`）。
- 下载响应带 `Content-Disposition: attachment`、`Cache-Control: no-store`、`X-Content-SHA256`。
- 快照内容不可变；再次导出会产生新快照。

### 界面

工作空间「导入与导出」页：导出（原因 + 密码二次确认 → 下载 CSV）与导入（粘贴 CSV → 预览逐行结果 → 整批确认）。导入表头与校验结果均显示位置字段，可展开查看当前单位已有的位置名称和 ID；位置名称不能代替 ID。

### 本次验证（2026-11-05）

- `DeviceBulkTest` 8 项通过：旧模板拒绝、空/不存在/跨单位/超长位置、整批拒绝、确认前重校验、位置入库、源哈希重放、导入快照不随设备当前位置改变。
- `DeviceLocationTest` 7 项通过；修正测试清场遗漏 `places` 导致的外键阻断，没有放宽业务断言。
- 前端生产构建与 JAR 打包通过。独立 Linux/H2 实例真实浏览器完成错误预览、合法导入；HTTP 确认整批拒绝后设备数为 0，合法导入及重放后设备数为 1，设备位置与任务行快照一致。
- 未以此声称数据库非空约束、位置子树授权或 PostgreSQL 运行验收完成。
