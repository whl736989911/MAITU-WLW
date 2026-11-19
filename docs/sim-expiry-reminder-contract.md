# N03：SIM到期站内提醒与处理（Frozen v2，实施合同）

## 已确认规则与边界

- 初始关闭、未配置不启用；纯日历DATE，显式IANA时区；提前天数、重复间隔天数、重复次数和收件范围均显式配置，无30天/午夜/日末默认值。
- 仅授权平台人员；每人已读独立，任一有处理权者处理结束整轮；首次启用/漏扫只补当前一条，不补历史多条；规则变化/停启不重开已处理轮，仅到期日变化或显式重开创建新轮。
- 来源为 `sim_cards.expires_on`（CARD）和 `sim_embedded_profiles.expires_on`（EMBEDDED）。提醒、处理、重开不改台账/绑定/库存，不冒真实续费、运营商停机、短信/语音/邮件、物理事实或审批。
- 收件范围用显式平台用户ID登记，不查询人员目录；PLATFORM的tenant_id本来为空，资格检查其启用状态、已改密、当前单位grant及sim-expiry:read。不暗授权限。

## V17五表（54→59）

V1..V16原字节保留，五表初始全空；仅对精确protected bootstrap-admin条件补三权限。

1. `sim_expiry_config`：tenant_id PK/FK、enabled、timezone/lead_days/repeat_interval_days/repeat_count（四列全空或全非空）、revision、updated_at。无行=虚拟关闭revision0、四配置null、updated_at null；enabled=true必需完整配置。停用可保留完整配置或显式清空四列。保存每次revision+1，不写虚拟0行。数值为规范JSON整数：lead_days/repeat_count >=0，repeat_interval_days>=1，上界仅Java INT_MAX技术表示范围；不添加业务阈值或有效默认值。日期运算按日差比较，避免远古/极大提前天数溢出。
2. `sim_expiry_recipients`：tenant_id,user_id复合PK/FK、added_at、added_by。添加校验现时资格，移除后不再收新提醒；既有历史不删。每次扫描/查询重新验证用户enabled/must_change_password/权限/grant；旧登记不等于永久授权。普通read用户仅见当前登记期间产生过通知的轮次（notice.created_at >= added_at）；重新登记不自动接收此前历史。管理者仍可查询全单位轮次，既有个人已读事实保留。
3. `sim_expiry_rounds`：id UUID PK、tenant_id FK、target_kind CARD/EMBEDDED、target_id、expires_on DATE、内部source_revision（创建时台账版本）、generation、state OPEN/HANDLED/SUPERSEDED、revision、reminder_count、last_reminded_on DATE、opened_at、handled_at/by/reason（仅HANDLED有值）、reopened_from（nullable FK旧轮）、reopen_reason（原文）。UNIQUE(tenant_id,target_kind,target_id,expires_on,generation)。初始generation=1、revision=1、reminder_count=1；显式重开创建相同日期generation+1新行，旧HANDLED行原样保留，依据在新行。每目标最多一个OPEN，由统一单位行锁保障；新一代的reads独立。source_revision不进入公开DTO，只用不可变台账历史判断日期真实变化。
4. `sim_expiry_notices`：id UUID PK、round_id FK、sequence BIGINT、noticed_on DATE、kind INITIAL/REPEAT、created_at；UNIQUE(round_id,sequence)与(round_id,noticed_on)。完整追加序列，与round.reminder_count（BIGINT）一致。总数上限=配置repeat_count+1（首条不算重复次数）；INT_MAX次重复的总数2147483648不得溢出为负数。
5. `sim_expiry_reads`：round_id,user_id复合PK/FK、read_at。首次显式已读追加，后续返回原事实；不推进轮次revision、不改他人已读；重复提醒不重置已读。

配置/扫描/收件范围/处理/重开采用同一锁序：安全管理锁（涉及用户资格）→当前tenants行锁→config/round/source行；同事务RC。成功业务写/审计/幂等原回执原子提交；GET用RO/RR，成功全59表零写。不得锁倒序；只扫描当前单位；worker各单位独立事务。

## 扫描与自动运行

- DB时间转换到配置时区得到today，只作日历判断；不把expires_on转换为午夜/日末。
- 关闭/无有效收件人：零业务写。损坏配置/源/轮次返回稳定503，不能伪装关闭或空列表。
- 同单位有日期的目标在today>=expires_on-lead_days且无OPEN时，首次建一轮+一条INITIAL；已有HANDLED日期不因规则/停启再次建轮。真实日期A→B→A也属于日期变化：以创建时source_revision和后续不可变台账历史确认不同日期曾出现，允许新generation；仅编辑无关字段不产生新轮。
- 扫描先检查OPEN对应的当前到期日。日期变更/null/目标不再存在则旧OPEN改SUPERSEDED（系统结束，不冒人工处理、不填handled字段）；新日期到窗口时建新轮；到期日变化即结束旧轮，不能让旧日期继续提醒。
- OPEN轮满足日差>=repeat_interval_days且count<repeat_count+1时仅追加一条REPEAT；漏扫不循环补发，last_reminded_on=today；同日多worker/重跑不重复。日期回拨不倒增。
- handle仅OPEN→HANDLED，原因必需，CAS+1。reopen仅最新且到期日仍匹配的HANDLED且无其他OPEN，新generation行+INITIAL，旧轮不改。
- 自动扫描用已有all/worker角色的SmartLifecycle模式，独立线程，复用runtime taskPollMs技术轮询间隔；配置初始关闭。api角色不运行worker；worker以数据库当前时间调用事务scanner，单位行锁保证多进程同日唯一，不用内存去重。显式扫描与自动扫描共用一个事务核心。

## 权限与API（管理端，X-Tenant-Id）

新增PLATFORM `sim-expiry:read`、`sim-expiry:handle`、`sim-expiry:write`。读需read+grant，handling需read+handle，配置/收件范围/扫描需read+write。当前有资格且登记为收件人的read用户只查看/标记自己收到的轮次；有handle或write的read用户可管理当前单位所有轮次，但未登记不会成为自动提醒收件人。客户无入口无权限。fresh安全和密码先于幂等重放；存储坏回执503。

所有成功JSON 200；分页精确items,total,page,size,pages。timestamp输出fixed6 UTC，日期YYYY-MM-DD。输入局部strictJSON拒重复/漏/未知键、负零/decimal/exponent/string integer、坏UTF16/控制符、尾JSON、写query、非JSON内容。opaque用户/设备ID精确保留，不trim变另一身份；reason最多现有Limits.MAX_CHANGE_REASON_LENGTH，保完整原文，拒仅空白（配置保存可空，处理/重开必需非空）。

| 方法 | /api/management/sim-expiry下路径 | 输入/输出 |
|---|---|---|
| GET | /config | 需read+write；Config: tenant_id,enabled,timezone,lead_days,repeat_interval_days,repeat_count,revision,updated_at |
| PUT | /config | enabled,timezone,lead_days,repeat_interval_days,repeat_count,expected_revision,reason,actor_password → Config；Idempotency-Key必需 |
| GET | /recipients?page&size | Page<Recipient>；仅write；Recipient: user_id,added_at,eligible（资格现查，不暴露姓名/密码/目录） |
| POST | /recipients | user_id,actor_password → Recipient；原key重放、相同user新key已在范围返回原登记 |
| DELETE | /recipients/{user_id} | actor_password → Removal: user_id,removed；已不存在返回removed=false；Idempotency-Key必需 |
| GET | /rounds?state&page&size | Page<Round>；state可选OPEN/HANDLED/SUPERSEDED，expires_on ASC,id ASC |
| POST | /scan | actor_password → Scan: today(nullable关闭),created,repeated,superseded；原key重放不重扫 |
| POST | /rounds/{id}/read | actor_password → ReadReceipt: round_id,user_id,read_at；read+有效收件资格或处理/配置权，原key幂等 |
| POST | /rounds/{id}/handle | reason,expected_revision,actor_password → Round（包括read_by_me/read_at） |
| POST | /rounds/{id}/reopen | reason,expected_revision,actor_password → 新Round；原key始终重放同新轮 |

Round精确字段：id,tenant_id,target_kind,target_id,expires_on,generation,state,revision,reminder_count,last_reminded_on,opened_at,handled_at,handled_by,handle_reason,reopened_from,reopen_reason,read_by_me,read_at。handle/reopen原回执的read字段是原提交快照，不承诺当前已读。所有写有Idempotency-Key；密码不进fingerprint/回执/日志/审计。跨单位/不存在404、权限403、CAS REVISION_CONFLICT/状态SIM_EXPIRY_STATE_CONFLICT 409、损坏SIM_EXPIRY_READ_UNCONFIRMED/SIM_EXPIRY_CONFIG_UNCONFIRMED 503、耗尽SIM_EXPIRY_REVISION_EXHAUSTED 409。

## 界面与真实出口

管理端lazy“到期提醒”页，客户无入口，只写权不暗得read；初始0新请求，明确查询读取轮次/配置/收件范围。不填任何提前量/时区/重复默认值；停用保留配置可见。每用户已读与全轮已处理明确分列，处理/重开理由、密码、二次确认、原key未知重试；409须显式重读，新scope/account/page/自助改密即销毁全部草稿/密码/意图，迟到body/reject/finally不能影响新owner；401仅当前owner清会话，403/404仅feature清范围。按钮按显式handle/write分别控制；返回回执不冒当前状态，不自动业务轮询。

验收：行为测试覆盖双来源、时区边界、禁用/配置/有效资格、漏扫单条、并发幂等、读状态独立、处理结束整轮、新代次保历史、到期日变更、CAS/原key/密码/当前撤权/坏回执、事务失败回滚；保旧697+800实际case，历史迁移测试pin16，当前全表精确59，不删除表过滤或放时限。Linux完整新包、H2/PG17真实API/Chrome与新原图，历史46+N03两存储新向量、退出资源清点、四旧Linux根/七历史包保护、文档来源闭合；未真验不称N03完成。
