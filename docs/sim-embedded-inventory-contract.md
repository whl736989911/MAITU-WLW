# P9a3 / N02：内置人工资料与独立人工库存（Frozen v1）
> 本文保留历史合同或验收记录；原始报告、截图、JAR及未公开合同不随公开源码发布，文中历史结论不代表当前版本验收。

## 1. 人工决定与不变边界

用户已实际确认：有权平台人员手工维护内置资料；可插拔卡入库/领用/回收/报废直接登记，无审批；解绑不回库、回收登记恢复可用。补充实问选择：内置资料归属已有设备，ICCID可未知，不另建第二套当前绑定；领用去向用普通文字，不暗接目录/绑定/卡所属单位转移；误报废允许有权人员填写依据后显式更正恢复，全部旧记录保留。

旧Card15/History17、固定REMOVABLE/MANUAL及原revision不扩字段；P9a2 association状态/历史/一机一卡不变。N02两个域均只是**人工记录**，不代表物理卡、实际插拔、库存盘点、运营商、付费方、在线或下行事实。内置资料不是新增当前卡关联权威；库存与当前关联可不一致，分轴说明；不因打开库存而自动读取association。只有另有device:read且用户显式另读才展示关联事实，否则标“关联未读取/无读取权限”，不借库存State推它。不能悄加“AVAILABLE才能绑定”或“先解绑才能回收”。无客户入口/权限，无审批、数量批处理、转单位、删除历史或真实外部调用。

N03另片按已确认显式时区/提前重复次数收件范围配置且初始关闭；本片不放占位提醒参数、不填30天默认，不把旧expires_on日历DATE转午夜/日末。

## 2. 持久化、权限与事务

仅新增V16四表：`sim_embedded_profiles`、`sim_embedded_profile_changes`、`sim_card_inventory`、`sim_card_inventory_changes`；50→54。V1..15/旧表数据不改，不回填内置资料、库存可用或绑定；profile以device_id为PK并复合(device_id,tenant_id)FK，inventory以card_id为PK并复合(card_id,tenant_id)FK。current显式UNIQUE(resource_id,tenant_id)供历史复合FK引用；历史同资源tenant FK/唯一(resource,revision)、内部actor_user_id及DB微秒时间；应用只追加，不宣称DB管理员防篡改。

新增PLATFORM显式`sim-embedded:read/write`、`sim-inventory:read/write`，V16仅protected bootstrap-admin条件补这四权，不补普通账号、不由旧权暗授。Profile读要求device:read+sim-embedded:read，写再加sim-embedded:write；inventory读要求sim-cards:read+sim-inventory:read，写再加sim-inventory:write。每次fresh当前单位grant与权限，写当前密码；安全锁→device或card锁→本域state锁，实际writable RC；成功写state/history/SUCCESS audit/idempotency同事务。业务依赖仅shared/identity公开设施及公共`configuration.DatabaseTime`，不import simcard/simbinding/device内部类或跨域写服务，其余configuration内部实现也不依赖。

每个**新人工登记意图**成功均revision+1/一个不可变登记，包括字段或状态相同；不借N01 changed/noop丢弃本次依据。明确文案“再次登记会留记录”，不存在changed布尔。原key重放fresh安全/密码后返回原完整回执，不再业务读取/DomainClock/新时间/历史；fingerprint含tenant/op/resource/expected revision/规范业务字段及完整reason，不含密码。不同意图不能借原key。未知仅原意图原key重试，GET不证明未知提交；409需严格显式当前读后才能发新意图。

## 3. 内置人工资料（simembedded）

- 设备ID是精确opaque VARCHAR64，不是UUID/EUI；拒空串/ISO控制/坏UTF16/超过64及Java U+0000..0020边缘空白，**不trim转换成另一设备ID**。NBSP/BOM按原N01合法保留，不NFKC。已有设备、同当前单位；不存在同404。不提供独立未归属资料或资料再归属/转移API。
- Current DTO精确15键：`device_id,tenant_id,iccid,carrier_name,installation_form,replaceability,management_party,fee_responsibility,reported_operator_status,expires_on,evidence_at,source_note,information_source,revision,recorded_at`。
- 固定installation_form=EMBEDDED、information_source=MANUAL表示本人工资料资源类型，不证明设备实际有内置卡。**无row**返回virtual revision0/recorded_at=null；iccid/expires_on/evidence_at=null，carrier_name/source_note空串，四报告枚举UNKNOWN；界面显著“尚无内置人工登记，不代表确认设备有内置卡”。有row revision1..MAX_SAFE且recorded_at非null，首次登记expected0→1；无删除/退回virtual0。
- iccid可null表示未知；已知值string经Java边缘trim后非空、≤64 UTF16、拒ISO控制/坏UTF16，不要求数字/不造占位号；可后续人工更正。它是未认证报告字段，不作为跨资料/旧卡实体合并或第二绑定依据，不加跨域ICCID唯一。carrier_name≤200/source_note≤1000，按旧台账相同输入语义（Java边缘trim，允许空串，note允许内部CR/LF/Tab），不import旧内部输入类。
- replaceability闭集UNKNOWN/REPLACEABLE/NOT_REPLACEABLE；management_party与fee_responsibility各自闭集UNKNOWN/PLATFORM/TENANT/MANUFACTURER/OTHER；reported_operator_status闭集UNKNOWN/ACTIVE/SUSPENDED/EXPIRED/CANCELLED/OTHER。不从EMBEDDED推其它轴。
- expires_on=null或0001..9999真实Gregorian YYYY-MM-DD/SQL DATE。evidence_at=null或四位年份UTC Z、秒必需、最多6位小数；输出规范fixed6微秒Z，不经JS Date/Number毫秒截断。不加未来/滞后等业务限制。recorded_at仅DB登记时刻，不是evidence/实际同步时间。
- GET `/api/management/embedded-sim-profiles?device_id=...`：恰一device_id，无其它query/body/Transfer-Encoding。
- POST同base，无query，精确13键且全部必须出现：`device_id,iccid,carrier_name,replaceability,management_party,fee_responsibility,reported_operator_status,expires_on,evidence_at,source_note,expected_profile_revision,reason,actor_password`。只有iccid/两个日期允许null。namespace `sim-embedded-record`；返回精确Current DTO，revision=expected+1/规范字段与意图匹配。
- GET `/api/management/embedded-sim-profile-changes?device_id=...&page=...&size=...`；device_id必须且唯一，page/size可选。
- HistoryItem精确8键：`change_id,device_id,tenant_id,revision,operation,profile,reason,recorded_at`；operation固定RECORD，profile为该登记的完整15键快照，device_id/tenant_id/revision/recorded_at须与外层一致且revision>0；change_id仅外层独立lowerUUID，不给profile私加第三身份字段。Page精确`items,total,page,size,pages`，total0→pages0；revision DESC/连续位置total-offset-i，不按时间排序，不联接当前人名/设备名冒过去。

## 4. 人工库存（siminventory）

- 资源只引用旧REMOVABLE卡(card_id,tenant_id)，不写旧metadata、history或association。State精确6键：`card_id,tenant_id,status,issued_to,revision,recorded_at`。
- 无row=UNREGISTERED/revision0/issued_to空串/recorded_at null，不能把旧台账全部当可用。有row revision1..MAX_SAFE、时间非null，status仅AVAILABLE/ISSUED/SCRAPPED。ISSUED的issued_to为非空普通文字；其它状态必须空串；操作者不冒领用人。
- GET `/api/management/sim-card-inventory/{card_uuid}` 无query/body；GET collection `.../sim-card-inventory?page&size&status` 只分页已登记row，status可选AVAILABLE/ISSUED/SCRAPPED，按card_id ASC；不为UNREGISTERED合成全卡rows。StatePage精确items/total/page/size/pages。
- POST `/{id}/receive`、`/recover`、`/scrap`、`/correct` 各精确`expected_inventory_revision,reason,actor_password`三键；`/issue`另加`issued_to`第四键；无query。独立namespace依次`sim-inventory-receive/recover/scrap/correct/issue`，成功返回精确State，revision=expected+1，必须有idempotency header。
- 非SCRAPPED（含UNREGISTERED）可直接人工receive→AVAILABLE、issue→ISSUED、recover→AVAILABLE、scrap→SCRAPPED；不擅加必须先补入库/先解绑等物理流程门。同目标状态仍是本次人工登记并+1留依据，不叫noop；issue可再次登记新的文字去向。
- SCRAPPED仅可再次scrap登记或**correct→AVAILABLE**；其它操作409明确提示需显式更正。correct只接受当前SCRAPPED，reason须Java边缘trim后非空且保完整原文，明确不是物理回收；不可悄用receive/recover擦掉报废。所有旧报废及更正记录不可变。
- issued_to≤1000 UTF16，Java边缘trim后非空，普通文本可内部CR/LF/Tab，拒NUL/其它ISO控制/坏UTF16；输出规范trim后值。不调用人员/单位/设备目录，不转所属单位。
- GET `/{id}/changes?page&size`；HistoryItem精确10键：`change_id,card_id,tenant_id,revision,operation,from_status,to_status,issued_to,reason,recorded_at`；operation RECEIVE/ISSUE/RECOVER/SCRAP/CORRECT，to_status与动作匹配，from_status可含UNREGISTERED；revision1恰from UNREGISTERED，之后from非UNREGISTERED，同页邻接/位置必须成立。issued_to只描述本次to状态，旧去向在之前历史保留。Page5与profile相同。

## 5. 公共输入、只读与错误投影

两个域局部strictJSON拒重复/未知/漏键、null/coercion、raw -0、decimal/exponent/string revision、尾JSON、错误Content-Type及写query，不改旧Jackson全局。expected revision仅词法JSON安全非负整数；数据库现存revision不可能/超界、UUID/time/枚举/UTF16/nullable组合损坏或重放坏DTO均safe503，不伪200/空0。MAX_SAFE exhausted拒写且全事务不变。

reason≤1000 UTF16，保完整原文（沿N01reason词法语义），允许内部CR/LF/Tab，拒NUL/其它ISO控制/坏UTF16；除correct必须有依据，其它可空。密码沿统一安全规则，仅私有请求体，不能日志/错误/审计/argv泄漏；自由文本普通转义显示，不解析HTML/URL/凭据字段，不把任意用户备注当已认证无秘密。

只读实际RO/RR同快照，成功全54表零写；原DENIED审计单列，history/pageSize配置损坏必须503，不借旧数据/默认值补空。显式size也验证集中page-size元数据，页长/页数资源限制沿既有集中技术政策，不新增无消费者业务设置。Current读取不分配时钟/写空row；纯COUNT/分页/最小资源存在投影，不查询或暴露用户目录。

HTTP成功只精确200/对应DTO；不存在或跨单位资源404，未获单位/权限403；冲突409，未确认503。错误沿`{code,message,request_id}`，不得SQL/堆栈/raw source。新code：SIM_EMBEDDED_NOT_FOUND/READ_UNCONFIRMED/REVISION_EXHAUSTED、SIM_INVENTORY_NOT_FOUND/READ_UNCONFIRMED/REVISION_EXHAUSTED/STATE_CONFLICT；CAS沿REVISION_CONFLICT。只正确命名约束可映射对应冲突，不将所有23505当同一事件。

## 6. UI和owner生命周期

内置入口在管理设备详情内，仅device:read+sim-embedded:read；库存入口在管理SIM台账选卡面板及独立已登记库存页，仅sim-cards:read+sim-inventory:read。全部独立lazy新模块、默认0新GET、不暗授客户/只写角色读权。页面显式读取；写按钮另独立write与密码/CAS确认，旧metadata/association及新inventory共busy/unknown写互斥，不用关闭重开洗旧gate，禁止任一表单异步结果覆盖其它owner。

普通内面板close/cancel以及同页**外设备详情Sheet关闭再开同设备**仅是展示关闭，不解除unknown/409门或换原canonical/key；须在条件挂载DeviceDetail之上的页级宿主保留最小意图/门，关闭立即清密码、取消展示请求并拒其迟到结果。允许最窄设备页/工作台owner桥接及type-only props，不为此把大新feature塞进eager入口。选择另一设备/卡才是对象owner变更；当前单位/账号权限/会话变更、离整页/自助改密真正页owner unmount必须销毁全部敏感数据/密码/草稿/intent，不用模块全局Map跨页/账号保存。body/decoder/reject/finally均核完整owner/op，即使内面板未挂载也由父级fresh context使旧owner失效。完整当前401才清session；当前403/404为本feature范围未确认，清并gate但不logout，旧owner401/403不能碰新owner。409仅显式严格当前读取可建立新意图资格，历史不当CAS；unknown仅原canonical+key重试，GET/close/cancel不解除未知。两写控busy防双提交；UI不得说保存回执是“现在”的数据。

## 7. 写scope和真实出口

写前666文件（637工程+29正式方案）、6历史JAR、N01已认证753raw全hash已实际0，见基线。旧Linux三根389/487/520及9704/5424/6211永久只读；仅新Ubuntu工作区构建/运行，工具缓存校验复用，不增加Windows入口。原1239测试/44原flags/旧依赖锁/V1..15不删弱；V16导致latest/schema耦合必须先有效迁移+旧源byte不变真实RED，Root另scope仅pin历史target15/精确50→54和对应其余计数派生，不能>=/过滤新表/改原业务断言/时限。

BE独占新simembedded/siminventory主代码与新SimEmbedded*/SimInventory*测试、V16与四新权限；Root旧耦合tests/脚本后授权。FE独占新feature lib/panels/库存页、新tests及必需设备详情/台账/shell最窄入口与owner互斥，Root不交写。Native/Chrome各仅新p9a3前缀私有夹具scope；Root独占协调合同/IPC/工具/基线/最终证据。任何跨scope/合同调整先报告，禁止Leaf Linuxcopy/build/长驻运行；Root统一冻结同步并实际Ubuntu单元/完整包/API/原生PG17/H2/真实sandbox Chrome/手机图/原44与ownedcleanup，未真测不能声明有限完成。
