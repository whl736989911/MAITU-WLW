# P4a：通用HTTP接收、原始报文与归一化预览合同

本文保留 P4a 阶段口径。**当前业务写入不提交 `actor_password`，接入来源只新增真实类型，无 `is_simulated` 创建参数。** 幂等重试仍重验当前会话、权限和单位授权；需要确认的变更使用页面二次确认，不重输密码。以下阶段性请求示例中的旧密码和模拟字段不属于当前接口。

## 本轮边界

承接P1/P2/P3全部约束。新增ingestion领域，依赖device/product/configuration/identity/shared；device/product不得反依赖ingestion，各域只写自己的表。追加V5，不改V1..V4。仅给受保护bootstrap根账号补新权限。

本轮是可鉴权、可留存、可去重的通用接收端及捕获版本的归一化预览，**不是厂商协议接通/自动业务处理**。不实现外部轮询、脚本、异步租约worker、生产重放、通知和下行。接收/预览均不写设备物理状态/最后上报/电量，不创建事件/报警/通知；不因源停用批量更改设备。后续P4b/P5需独立完成可信排序、恢复关联、outbox/租约/业务推进后才可开启自动应用。届时模拟源的业务应用仍须全局模拟开关、simulation权限及明确模拟目标，sources权限不能替代这些门禁。本轮尚无报文保留期/清理任务，不宣称默认30天保留或永久原文可用。

新增环境开关APP_GENERIC_INGEST_ENABLED默认false，仅all/api允许通用捕获；ingest/worker角色本轮仍不承诺实际消费者。health新增generic_ingest_enabled或等价明确capability；页面显式“仅接收/预览，不推进设备和报警”。关闭开关时接收503，管理读取/配置仍可用。不改APP_SIMULATION_ENABLED含义，不污染P1模拟入口。

## 权限与领域隔离

权限：sources:read、sources:write、sources:bind、sources:credentials、messages:read、messages:raw、messages:preview，均仅PLATFORM；客户本轮无来源/原报文入口。管理端均按当前有效X-Tenant-Id过滤，单位必须启用、当前账号获授权。绑定另需device:write。

管理写操作继续actor_password、Idempotency-Key、当前DB权限/单位重查、协调锁、事务审计；可更新对象使用expected_revision。403不退出有效用户会话，409刷新重开。原文权限独立于列表/预览权限；任何无messages:raw响应不含payload或raw_event。

原始接收端不用管理Bearer会话，不接受调用方指定tenant_id/device_id/产品版本。source_id确定单位，再由(source_id,vendor_device_id)确定显式绑定。URL或body不能伪造单位。管理用户Bearer不能充当接入凭据，接入凭据也不能访问管理API。

锁顺序需与现有身份/单位停用协调：管理协调锁→actor→tenant→source→device（公共只读加锁服务）→接入自有行。接收先非锁查询来源以取得tenant，然后锁启用tenant→source并重验凭据/启用状态→必要时device。不以source→tenant反序制造停用/绑定死锁。只能通过identity/device的公开服务锁其行，不跨域直接写表。

## 接入源与凭据

Source={id,tenant_id,name,protocol:"GENERIC_HTTP_JSON_V1",is_simulated,enabled,revision,created_at,last_received_at:null|time,message_count,duplicate_count}。tenant/protocol/is_simulated创建后不可变。revision仅配置/凭据/绑定变更版本，接收统计分开，不因每次接收使编辑必然409。只支持平台自有通用格式，不提供假厂商下拉。

- GET /api/management/sources?page=1&keyword= -> Page<Source>，sources:read。
- POST 同路径 {name,is_simulated:false|true,actor_password} -> CredentialResult，sources:write；默认真实类型false，明确模拟才true。初始enabled=true。
- PUT /api/management/sources/{id} {name,enabled,expected_revision,actor_password} -> Source，sources:write。
- POST .../{id}/rotate-token {expected_revision,actor_password} -> CredentialResult，sources:credentials。

CredentialResult={source:Source,one_time_token:string|null,credential_recovery_required:boolean}。服务端SecureRandom生成至少256bit不可预测随机令牌，使用gih_前缀与用户会话令牌格式隔离；仅首次成功响应传明文，数据库只存SHA256及token_generation，不存明文/可逆密文。旧令牌在轮换提交后立即失效，停用源/单位拒绝全部令牌。

幂等账本只存无秘密的Source结果；首次创建/轮换的业务操作只做一次。相同key重试返回同一业务结果、one_time_token=null、credential_recovery_required=true，明确“首次响应可能丢失，无法找回，请显式轮换”，不偷偷创建第二个源或静默轮换。换业务参数同key仍409。每次重试仍重验当前actor密码/授权。

秘密不得出现在列表、URL/query、审计、日志、持久浏览器存储、幂等结果或错误。凭据响应Cache-Control:no-store。前端一次性独立抽屉只内存显示/显式复制；关闭、切单位、登出销毁；不自动复制/下载/记录。curl示例只放占位符，不把令牌拼入持久示例。公网使用必须另行配置HTTPS，本轮不是公网生产上线门禁。

## 显式主源绑定

Binding={id,source_id,device_id,vendor_device_id,is_active,created_at,ended_at:null|time}；保留历史，不虚构旧映射。一个设备同一时刻仅一个主源；同一source的vendor_device_id只能绑定一个设备。跨源冲突不能静默抢占，需显式解绑后再绑。绑定不迁移产品版本。

- GET /api/management/sources/{id}/bindings -> {items:Binding[]}，sources:read；含该源历史及active状态。
- POST .../{id}/bindings {device_id,vendor_device_id,expected_revision,reason,actor_password} -> {source:Source,binding:Binding}，sources:bind+device:write；该设备同单位、明确来源类型与源一致、已绑定已发布产品版本。vendor_device_id为有集中长度边界的不透明标识，不假设16hex，不自动用EUI匹配。
- POST .../{id}/bindings/{binding_id}/detach {expected_revision,reason,actor_password} -> {source:Source,binding:Binding}，sources:bind+device:write。原因必填、审计并保留结束时间；旧Raw快照不改。已解绑不同key再解绑409，相同key重试返回原结果。

source revision在绑定/解绑时递增一次，用最新revision后续操作。active指针及历史表分离或等价portable约束；H2/PG都必须用数据库唯一约束防并发多主源，不能只依赖前端。

## 通用捕获端

POST /api/ingest/{source_id}/messages，Authorization: Bearer <一次性接入令牌>，application/json：

```json
{"source_message_id":"opaque-message-001","vendor_device_id":"opaque-vendor-device","reported_at":null,"payload":"{\"event\":\"FIRE\",\"battery\":0}"}
```

source_message_id必须调用方提供稳定身份，缺失400，不用received_at伪造幂等键。payload必须字符串，保存其原始UTF8内容/字节数/SHA256，不将其JSON.parse再序列化；支持未知/畸形JSON供诊断，不截断。reported_at为null或有界字符串，原值保留；解析时间质量在预览标注，不能把回退接收时间伪装真实设备时间。错误envelope/未知字段/重复外层键400。

单条payload集中上限与P3解析资源边界兼容（64KiB），接收总请求实际字节上限沿用共享2MiB，包括chunked。已识别的password/token/secret/authorization等字段拒绝且不落原文/日志；可复用线性敏感字段扫描，不承诺识别所有秘密。接收记录不保存Authorization或任意HTTP headers/query。未经鉴权、停用源/单位请求401或403（统一固定代码，不泄露对象信息），不保存raw。

事务内：鉴权/权限围栏→以(source_id,source_message_id)唯一键查重→若有当前绑定，锁设备并捕获当前已发布product_version_id及device_id/binding_id/设备名称地址快照→存Raw及接收投影→COMMIT后才200。

返回Receipt={raw_message_id,source_id,source_message_id,binding_state:"BOUND"|"UNBOUND",parse_status:"NOT_PREVIEWED",business_status:"NOT_APPLIED",deduplicated:boolean,received_at}。重复同身份且vendor_device_id/reported_at/payload完全相同：同raw_message_id和首次received_at、deduplicated=true、计数+1，不新建记录。相同身份不同内容409 MESSAGE_ID_CONFLICT，既有raw及预览证据不改。唯一约束覆盖跨到达时间，不按received_at分区/组合替代。disabled/token已失效时重传也拒绝，不能先命中缓存后跳过鉴权。

未绑定vendor_device_id仍可鉴权留存，Raw设备/产品版本快照为null，binding_state=UNBOUND；不会自动开户或用最新产品解析。后绑定不回填旧raw，旧无绑定本轮暂不能预览；后续有显式重解析合同才可解决。

## 原报文与预览

source_name与设备名称/地址均为接收时快照；后续源/设备改名不改原始事实。首次Receipt必须从落库行读取received_at，避免PG微秒精度与Java首次ACK纳秒值不一致。

RawSummary={id,source_id,source_name,source_message_id,vendor_device_id,device_id:null|string,device_name:null|string,binding_id:null|string,product_version_id:null|string,received_at,reported_at:null|string,payload_bytes,payload_sha256,binding_state,duplicate_count,revision,latest_preview_id:null|string,parse_status:"NOT_PREVIEWED"|"PREVIEWED",business_status:"NOT_APPLIED"}。payload只在单独原文端点。revision仅预览投影改变递增，接收重复统计不改变revision。

- GET /api/management/messages?page=1&keyword=&source_id=可选 -> Page<RawSummary>，messages:read；来源过滤器只能当前单位。
- GET /api/management/messages/{id} -> {message:RawSummary,previews:PreviewSummary[]}，messages:read；不含payload/raw_event。
- GET .../{id}/raw -> {id,payload,payload_bytes,payload_sha256}，messages:raw（建议同时messages:read），Cache-Control:no-store；只返回当前单位，不能用设备/源ID绕过。
- POST .../{id}/preview {expected_revision,reason,actor_password} -> {message:RawSummary,preview:Preview}，messages:preview；要求捕获时有已发布版本。使用捕获时版本，不随当前设备迁移而换schema；源停用或旧绑定已解绑仍可人工历史预览（本轮无业务副作用）。当前单位/当前actor仍须有效。
- GET .../{id}/previews/{preview_id} -> Preview，messages:read；若没有messages:raw必须删掉raw_event，其余安全diagnostics/事件类型/电量/时间质量可见。

Preview={id,raw_message_id,product_version_id,event_type:"FIRE"|"RECOVERY"|"UNKNOWN",battery_percent:number|null,diagnostics:string[],reported_at:null|time,time_quality:"REPORTED"|"MISSING"|"INVALID",raw_event:可按权限缺省,created_at,actor_id,reason,business_status:"NOT_APPLIED"}。以P3固定JSON Pointer算法解析同一原始payload（非合成样例覆盖/发布测试），只创建新的不可变预览尝试和最新投影；不覆写raw、旧尝试、设备/事件/报警。畸形/未知结果200并UNKNOWN/diagnostics，绝不当正常或恢复。无绑定/未知版本409且不新建尝试。report时间须ISO带时区且UTC instant在0001-01-01（含）到10000-01-01（不含）的集中可移植存储边界；REPORTED仅表示来源声明未校准，不表示可信实时/排序证据。有界合法时保留，否则null并标明MISSING/INVALID；不以接收时间假冒真实发生时间。

幂等同key返回同一尝试，不重复计数/新增，改reason/revision同key409；返回按当前raw权限删字段，即使第一次请求有原文权限、后已撤权，也不能从缓存泄露raw_event。预览概要不含raw_event；不要在通用错误/敏感操作日志输出raw_event/payload。

## 前端

管理端按权限增加“接入来源”“消息追踪”，均需选单位。来源真实API分页、创建/启停/改名/轮换令牌、一次性令牌只内存保存、显式绑定/解绑历史。绑定设备从当前单位分页查询，只选类型匹配且已发布模板的设备，不默认选择、不抢占其它主源。源id形成通用回环/部署URL示例，只展示凭据占位符；不声称拓宝/海康/麦途协议就绪。

消息页列表/详情均以真实API；原文独立按钮，仅有raw权限时请求/展示，切单位/关闭销毁。预览须二次密码/理由/当前revision，明示只用捕获版本、不推进设备/报警；历史尝试不可变，UNKNOWN/null不画正常/0。无messages:raw的读取与预览响应不含raw_event。数值JSON仍保留无损原文策略，尤其raw_event与精确电量，不经JSON.stringify改变原文。未绑定消息显示待绑定，但不暗示已有历史自动补处理。

## 验证出口

保留后端52/前端48及P1/P2/P3实测；新增V5升级/root-only权限、来源角色/单位隔离、随机凭据不持久明文/只显示一次/丢响应重试/轮换撤销、source/token与user/session双向不可混用、停用后重试拒绝、唯一主源及模拟/真实隔离、未知设备留存、跨时间去重/冲突保留原始、捕获版本在迁移后不漂移、畸形与敏感报文处理、原文权限不能被列表/预览/幂等缓存绕过、所有捕获/预览均无设备/报警/事件/在线时间副作用。Lead完整JAR跑H2、原生PG17/prod、真实Chrome；不以MockMvc代替PG或浏览器证据。
