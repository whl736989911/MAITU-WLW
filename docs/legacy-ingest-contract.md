# 旧平台只读镜像接入合同（Frozen v1）

> 依据：`omp-docs/00–19`（现状证据）与 `appendix/`（DDL、字典 TSV、64 个解码器、测试向量）。
> 用户已确认：**新平台首先要能接入这套平台的数据**；事实源为**只读镜像**，永不回写旧系统；技术栈可沿用本项目，架构参考 `reference/20`。
> 本合同只覆盖"把旧平台数据变成新平台事实"，不含旧系统下线、回写、支付、视频、巡检、档案。

## 1. 方向与边界

- 新平台只**读**旧系统：MQTT 订阅、HTTP 回调接收、只读库/导出文件。任何路径都不得产生对旧库或旧主题的写操作。
- 旧系统仍是唯一事实源；新平台自建事实库，冲突以旧系统为准，不做双向合并。
- 不接真实短信/语音/微信 provider；不建立旧 `/api/tsf/**`、`/api/iot/**` 的完整兼容层（按 `20.8` 仅保留接收侧）。
- 现有 `devices`/`products`/`product_versions` 语义不变；兼容能力以**新增**入口、**新增**版本格式、**新增**映射表实现，不改旧表语义。

## 2. 接入面（必须实现，缺一不算接上）

| # | 入口 | 旧系统证据 | 新平台落点 |
|---|---|---|---|
| A | MQTT 上行 `iot/{x}/{deviceId}/uplink` | `14.3`、`06` | 订阅适配器 → 原始消息 → 解析任务 |
| B | MQTT 上行 `gateway/{mac}/uplink`（TLV，tag `65`/`4D`） | `14.3` | 同上，走 TURMASS 语义 |
| C | HTTP `POST /api/notify/v1/{appToken}/{platform}/telemetry` | `02.5`、`14.1/14.2` | 兼容接收端点（`aep_turbo`、`lorawan_turbo`、`tyy_turbo`…） |
| D | HTTP `POST /v1/formttl/{messageType}/telemetry` | `14.8` | 兼容接收端点（Alarm/Fault/OffLine/HearBeat/Param/Other） |
| E | LoRaWAN NS connector JSON | `07`、`14.1` | 与 C 的 `lorawan_turbo` 同一入口，字段见 §4 |

下行（`/in/{deveui}/downlink`、`dnlink`、`down_{deviceId}*`）**本期不实现发送**，只在数据模型与命令中心预留（N10）。

## 3. 标识映射

旧系统同一台设备有多种标识，且 `d_device_id`、`d_eui` 在各自库内均唯一（14,882 / 14,886）。

| 旧字段 | 新平台 | 约束 |
|---|---|---|
| `d_device_id`（TTL）/ `tsf_device.d_device_id` | `devices.device_eui`（主外部标识） | 已有 `UNIQUE`，直接沿用，不新建列 |
| `d_eui` | `device_aliases`（`kind='EUI'`） | 新增别名表，唯一约束 `(tenant_id, kind, value)` |
| `devaddr`（LoRaWAN） | `device_aliases`（`kind='DEVADDR'`） | 同上；仅 LoRaWAN 设备存在 |
| `imei` | `device_aliases`（`kind='IMEI'`） | 同上 |
| `d_company_id` | `devices.tenant_id` | 36 个单位 1:1 映射为租户；**单位层级本期拍平**，`c_parent_id` 只作备注 |
| `place_id` | `devices.place_id`（可空） | 新增 `places` 表，承 296 个场所 |
| 空 / 0 的 `d_company_id` | `tenant_id='UNASSIGNED'` | `23.9` 明确存在此类数据，不猜归属 |

TTL 与 TSF 设备数相差 4 台（`23.9`）：以 TTL 为准建档，TSF 多出的 4 台进**对账差异表**，不静默丢弃、不自动合并。

## 4. 报文形态（按归档向量实现）

`appendix/test-vectors/` 已给出 8 类真实形态，作为首批契约测试：

| 向量 | 用途 |
|---|---|
| `lorawan_uplink.json` | 入口 E/C（`lorawan_turbo`）字段：`deveui/data/devaddr/fcnt/battery/port/datetime/datr/codr/mac/lsnr/rssi/best_gw` |
| `aep_uplink.json` | 入口 C（`aep_turbo`）：`upPacketSN/deviceId/productId/payload`，`APPdata` 可能 Base64 |
| `turmass_uplink.json` | 入口 B：`gateway/{mac}/uplink`，TLV tag `65`/`4D` |
| `ttl_to_tsf.json` | 入口 D：TTL→TSF 的 `messageType` 分流 |
| `tsf_alarm.json` | 入口 D：告警/故障 |
| `tcc_sms.json` | 通知意图（只做接收与状态，不发真实短信） |
| `export_request.json` | 导出请求（N12 消费） |

- LoRaWAN 外层 `battery=255` 表示未提供电量，不更新设备电量；真实 `0` 仍是有效读数，原始报文不改写。
- CAT1 兼容 `platformMsg.time` 提供的来源时间（已观察到 Unix 秒）；未提供可解析来源时间时仍保持未知，不以接收时间替代。
- TYY/OceanConnect：优先取顶层 `deviceId`，缺失时取 `service.deviceId`，不以网关标识代替。有效 `eventTime` 按顶层、`service`、`service.data` 顺序选取；载荷仍交给匹配的型号解码器。

**原始报文必须原样落库**（payload + 到达时刻 + 入口 + 标识），再做标准化。任何解析失败保留原文并可重放，不允许"解析失败就丢"。

HTTP 镜像保留原始 body 和基于 body 的去重。新 spool 记录的 `original_uri` 只保存规范路由（已知 TTL 平台的令牌位固定为 `{redacted}`，CAT1 只保留解析类别，未知路由使用入口标识）；`headers` 只保留白名单内的 `Content-Type` 主类型，不保留参数、原始代理 URI、Cookie、认证头或其他转发头。旧记录仍可处理，不做批量改写。

## 5. 产品与解码双格式

| 版本格式 | 适用 | 内容 |
|---|---|---|
| `JSON_POINTER_V1`（现有） | 新设备 | schema + capabilities + 阈值参数 |
| `LEGACY_ATTR_JS_V1`（新增） | 旧 77 个产品中的 64 个有解码器者 | 绑定旧属性目录（3,053 条）+ 解码器引用 |

- 旧属性目录写入 `product_version_attributes`，不参与新版本发布。已支持的数值监测字段按本产品版本的 `pa_flag` 匹配上报，以 `pa_id` 保留源记录身份；按字典展示名称与单位，不猜缺失单位。数值可来自数字或可解析的数字文本。服务、事件、设备识别字段不会因为出现在旧字典中就成为监测指标。
- 13 个无 JS 的产品：标记 `DECODER_MISSING`，报文进"待解码"队列，不伪造解析结果。
- 解码器执行：**独立受限进程**（无文件/网络访问、CPU 与内存预算、超时即终止）。单条失败只影响该条消息，主服务不受影响。
- 迁移路径：`沙箱执行 → 用测试向量逐条比对 → 该产品改声明式 → 移除其 JS 引用`，仅当向量全部一致才允许移除。

## 6. 标准化与事实应用

标准化事件（与 N06 对齐）：`tenant`、`device`、`source`、`received_at`、`observed_at`、`kind`（heartbeat/alarm/fault/param/other）、`events[]`、`raw_ref`。

- 旧 `msg_type` 映射：`Alarm`→告警、`Fault`→故障、`OffLine`→连接、`HearBeat`→心跳、`Param`→参数、`Other`→其他。
- CAT1 的 `/v1/formttl/alarms/telemetry` 是共用回调，不以 `alarms` 路径判定告警。优先采用正文 `parseMsg[].method`、`messageType` 或已识别的火警码；没有分类证据时保留为 `OTHER`，不补造来源时间。
- 设备六态映射：`1` 离线、`2` 在线、`3` 报警、`4` 故障、`5` 未激活、`6` 屏蔽，与现有 `connectivity`/`physical_state`/`integration_state` 分离保存，**不合并成单一状态字段**。
- 告警码：旧 `device_state_code`（已观测 `1001001/1002006/1002045/1001011` 等，完整码表未知）进 `legacy_alarm_codes` 映射表；未知码**原样保留并标记 `UNMAPPED`**，不猜测语义。

### 6.1 主状态显示口径（用户已确认）

设备列表/卡片只显示**一个主状态**，按以下优先级取第一个成立者：

```
火警 > 离线 > 屏蔽 > 故障 > 在线 > 未激活
```

其余事实（电量低、未处理报警条数、屏蔽原因、最后上报时间）作为角标或次要列显示，**不参与主状态竞争**。

存储层必须保持各轴独立（连接/物理/故障/屏蔽/处置/通知），合成只发生在前端展示与统计口径，理由：旧系统单一 `d_status` 会在"离线但仍有未处理火警"时把火警掩盖掉。

该优先级的三个直接后果，已确认接受：

1. 屏蔽中的设备若掉线，主状态显示**离线**而非屏蔽（离线优先于屏蔽）。
2. 屏蔽期间发生的故障**不显示为主状态**（屏蔽优先于故障）。
3. 收到过任何消息的设备不再显示**未激活**（在线优先于未激活）。

## 7. 去重、顺序与幂等

- 幂等键优先级：入口自带的 `upPacketSN` / `frid` / `messageId` > `(device, fcnt)` > `(device, observed_at, payload_sha256)`。
- 重复到达不重复产生事实，只记 `duplicate_count`。
- 乱序：按 `observed_at` 排序应用，迟到消息更新历史但不回退"最新值"。
- LoRaWAN 帧计数回绕/重放：`fcnt` 非单调递增时记异常，不静默覆盖。

## 8. 验证资产与出口

| 资产 | 用途 | 位置 |
|---|---|---|
| 8 个测试向量 | 入口契约测试，逐条断言 | `appendix/test-vectors/` |
| 64 个解码器 + `index.tsv` | 解码金标准 | `appendix/codecs/` |
| `db-schema.sql` + `db-tables.md` | 只读对账、字段语义 | `appendix/` |
| `legacy/*.tsv` | 字典/设备导入夹具（真实 PII，**不入版本树**） | 仓库外只读 |
| `endpoints.md` | 接收侧路径与字段 | `appendix/` |

出口（全部可实际执行）：
1. 每个向量走完"接收 → 原文落库 → 标准化 → 事实应用"，断言标准化结果与向量期望一致。
2. 用旧字典 TSV 导入设备与产品，输出**对账报告**（导入数/差异数/未映射数）。
3. 至少一个旧产品用原 JS 在沙箱解出真实报文，且该产品改声明式后向量结果逐字节一致。

## 9. 明确不做

- 不回写旧库、不改旧主题、不共享旧 MQTT 账号。
- 不实现旧 `/api/tsf/**`、`/api/iot/**` 全量兼容层。
- 不发真实短信/语音/微信，不接支付。
- 不做视频、巡检、消防档案。
- 不把 `appendix/legacy` 的真实设备/单位数据提交到任何远端。

## 10. 仍待确认（不阻塞开工）

1. 旧系统真实只读凭据（MQTT 只读账号 / 只读 MySQL / 是否允许被动收回调）——拿到后只换适配器配置。
2. 未知告警码的完整码表（当前按 `UNMAPPED` 原样保留）。
3. `d_heartbeat` 的单位（文档推断为秒，`18.5` 标注待核）——离线判定暂不启用自适应算法，沿用现有集中配置。
