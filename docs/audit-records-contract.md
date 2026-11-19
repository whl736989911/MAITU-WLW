# P8d1：当前单位操作审计的最小只读查询
> 本文保留历史合同或验收记录；原始报告、截图、JAR及未公开合同不随公开源码发布，文中历史结论不代表当前版本验收。

## 状态与基线

- **Frozen v1，Root task53完成只读审查后释放独立实现。** backend task54与frontend task55已交完整审查，无未决业务政策门；61动作/13结果、唯一UNCLASSIFIED及V10专测两token setup例外已明确冻结。合同本身不是代码/测试通过结论。
- 原P8c1 8EEB完整包、662/36闭合保留。Root实施前377文件观察actual0：原43 Java测试源（42测试类+helper）、18前端测试文件、44静态/332 Java21类/11迁移同原包；不把旧结果算作新源运行结果，也不是Git历史真实性证明。
- 自托管、无真实厂家/通知/云资产；不改DSH。用户不做领取、直接CAS提交、业务数值须有真实消费者与集中配置的既定约束不变。
- Root实施状态（非改变Frozen v1）：AD55完整737包/byte已actual0，owner FE434无独立原full.log的权威边界已记录；新H2/nativePG同包真实Chrome首轮均已collect0，八原图逐张read并原byte归档。原36已同AD55按原flags全actual收0，最终737/38/八原图闭合、运行后更严格包byte复核及17:44:24+08只自有清理均actual0，保PG18/DSH/诊断目录；以验证记录为准，只是本地有限验证而非生产/完整取证或全面安全认证。

## 1. 最小功能与非目标

只读现有 `audit_log` 中当前单位的实际已持久记录。新增管理端明确lazy导航“单位操作审计”，只做筛选、总条数、分页、手动刷新/取消；**不另造九指标大盘、详情/Sheet、导出、删除、已读、轮询或自动重试**。

不查询用户名/设备名/联系人目录，不展示原始 `request_id`、raw目标路径、请求/响应body、before/after、原因、凭据或连接串。身份UUID只能作为不透明历史引用，不声称该账号/对象仍存在、当前可访问或当前状态不变。无客户页面/端点，不将当前单位查询冒充全平台安全日志。

`tenant_id IS NULL` 的全局登录/产品/全局设置等记录不进入本接口；既有RequestContext对普通平台管理4xx通常只持有identity.user.tenantId（null），这类拒绝也不会因浏览器指定了单位就自动成为该单位记录。此次不改producer补归属、不声称展示该单位所有拒绝。缺失历史不补造。当前源表没有before/after与原因，不假装已有完整变更比较、完整操作追踪、不可抵赖或防数据库管理员篡改能力。展示结果是原审计事件的有限分类，不等同当前业务状态或外部送达。

## 2. 权限与迁移

新增 `audit:read`：仅PLATFORM，遵现P2当前权限和单位grant，不继承其它读权，不要求 `tenants:read`/`device:read`/`users:read`/`settings:read`。客户默认四权及可委派九类集合原byte不变，客户不得被授此权。

最窄既有后端改动是集中[Limits](<../backend/src/main/java/com/fireplatform/shared/Limits.java>)平台权限目录与[PermissionCatalog](<../backend/src/main/java/com/fireplatform/identity/PermissionCatalog.java>)标签；追加V12迁移只给满足既有 `id='bootstrap-admin' AND user_type='PLATFORM' AND is_protected=TRUE` 的受保护根账号补该权限，NOT EXISTS防重。新bootstrap沿现集中权限目录消费，不改其它开户/委派流程。原V1..V11、CUSTOMER_KEYS/CUSTOMER_PERMISSIONS、原所有audit producer/AuditService、原业务权限不动。

不新建表、不回填/修改审计事实；V1已有当前单位/时间索引，本轮不新建索引，不作生产大表迁移/性能证明。

每次请求fresh identity/role/permissions/tenant grant/单位启用/首次改密/ready；guard先于业务查询。worker-only/存储未就绪503与账号无权分开；不因模拟开关、通知worker/密钥关闭而禁止历史读取。管理端必须有效 `X-Tenant-Id`；body/query不能改租户范围。客户访问management按既有audience规则拒绝，customer没有新增等价路由。

## 3. 单一GET与过滤

`GET /api/management/audit-records`

只允许八个query键：`page,size,action,outcome,actor_ref,target_ref,occurred_from,occurred_to`。未知/重复键、正Content-Length或任意Transfer-Encoding在读取body前拒绝；不吞非法filter当空条件。

- page缺省1、size缺省读取实际 `default_page_size`；严格整数token、现集中PageSizePolicy/安全上界，不新增默认条数、时间窗口或数值配置。原全局页大小消费者继续，技术上界仍Limits统一。
- action/outcome仅冻结闭集代码或 `UNCLASSIFIED`；可省略，空字符串标准化null。不得接受自由动作SQL/搜索/任意keyword或unknown原值。
- actor_ref/target_ref仅完整canonical lowercase UUID，可省略/空转null；不能用用户名、路径、前缀、电话号码或非UUID标识作检索。存储引用合法大写UUID时读取/过滤统一casefold；不放宽为UUID缩写。
- occurred_from/to：显式offset、0001..9999、微秒精确可表示；canonical UTC回显。from严格小于to，`[from,to)`，单边可用、没有默认日期；沿P8c1一致精度约束，不能取整/自动交换/删掉非法边界。
- filters固定六键，不含page/size：`action,outcome,actor_ref,target_ref,occurred_from,occurred_to`，省略均null。
- tenant限定 + 六条件AND；分类条件必须用与投影一致的CASE，`UNCLASSIFIED`读所有未在本次有限注册表内的源值，不是查raw literal UNCLASSIFIED。未知源值不原样回显、也不冒SUCCESS/DENIED。

## 4. 固定安全投影

Row固定八键：

| 键 | 语义 |
|---|---|
| id | audit_log自身canonical lowercase完整UUID，唯一行标识，不从request_id代替 |
| occurred_at | 实际持久源时间，UTC微秒，不是设备时间/提交排序水位 |
| action | 冻结闭集安全动作代码，否则UNCLASSIFIED |
| outcome | 冻结闭集安全结果代码，否则UNCLASSIFIED；不能只假定SUCCESS/DENIED，worker实际结果须穷举 |
| actor_ref | 可公开的UUID引用；否则null |
| actor_reference_state | UUID / NOT_RECORDED / WITHHELD |
| target_ref | 可公开的UUID引用；否则null |
| target_reference_state | UUID / NOT_RECORDED / WITHHELD |

引用状态规则：源NULL→NOT_RECORDED/null；符合完整UUID格式→UUID/lowercase UUID；其余任何非空或空字符串→WITHHELD/null。WITHHELD不等于未记录，可能是固定系统标识、路径或不安全自由文本；绝不回显原文/前后缀/长度。bootstrap-admin属于WITHHELD，不为展示姓名去额外读账户目录。两个state=UUID当且仅当对应ref为合法非null UUID。

仅显式选择上述投影所需源列；request_id根本不SELECT。未知action/outcome以常量安全分类，不把unknown原文传给Java/序列化/错误日志；actor/target不安全值也应尽可能在SQL CASE投影层置null再回Java。非UUID审计行id、坏/越界时间、非法字段形状、整数溢出/不安全总数等不能造0/漏行，整次safe503 `AUDIT_READ_UNCONFIRMED`，不回显异常值/SQL/秘密。

## 5. 响应与同快照口径

成功无store缓存。Top固定十一键：`items,total,page,size,as_of,timezone,scope,filters,action_definitions,outcome_definitions,coverage`。

- scope固定两键 `{tenant_id,audience:"management"}`，是已fresh授权当前单位，不回显session/token/目录名称。
- timezone固定 `Asia/Shanghai`；wire时间canonical UTC，页面明确北京时间输入与UTC原始微秒显示/说明，不拿本机时钟推演事实。
- action_definitions/outcome_definitions各是冻结有序 `{code,label}` 列表（包括唯一UNCLASSIFIED，原值不展示），来自代码常量，不根据审计库DISTINCT值生成、不为此另发目录请求；精确62/14项见第8节冻结附录，按源code ASCII升序、UNCLASSIFIED末项。
- coverage固定五键：`{source:"PERSISTED_AUDIT_LOG",scope:"CURRENT_TENANT_ONLY",missing_history:"NOT_RECONSTRUCTED",references:"UUID_ONLY",raw_request_id:"NOT_EXPOSED"}`。
- total为所有匹配页记录数，不是全单位全部动作数、报警数或审计完整性证明。固定sort `occurred_at DESC,id ASC`，OFFSET页各有独立可移动快照，不承诺跨页无重无漏。as_of为DB wall观测，不作commit watermark，也不额外以as_of截掉源行。
- 真正readOnly/repeatable-read事务，fresh guard后业务application SELECT固定四条：实际pageSize设置、DB wall、同谓词count、page。身份重查SELECT/框架BEGIN等分列；不是native wire计数。无join、N+1、FOR UPDATE、Java全库扫描、SELECT *或写事务。
- 成功GET不写业务、队列、audit、幂等或其它表。4xx遵既有DENIED审计（它可能被后一次授权读取看见），safe503不增DENIED；不能以整轮包含登录/授权/夹具/拒绝写的hash冒成功GET零写。

## 6. 最小前端与范围屏障

新lib/page/test，既有App/types仅加lazy路由、management-first `audit:read` gate、requiresTenant；尽量复用已导入图标。主eager495151/500000上界不变，不把新页面拉入eager。旧18测试文件394断言、两worker/隔离/timeout、P7/B1-B3/P8c1默认请求/草稿/操作语义原byte不动。

只显式导航进入后、当前platform+permission+tenant已具备才GET；无权限不渲染行，客户无导航。audit-only无tenants:read可沿原指定未核验单位ID壳走当前单位真实授权，不借其它业务目录。

独立scope-local GET，完整token/user/generation/permissions/audience/有效tenant/must-change-password、signal/op，在status/401副作用和body读取/解析/采用前后均guard。旧同token新generation晚401/403/body不影响新owner；当前403清本视图不logout，当前401按既有当前身份失效。切单位/权限/身份/关闭取消不留旧行；不引入轮询/任意默认timeout/重试链。

draft/applied分离；显式应用/清空/翻页/刷新/取消，翻页/刷新沿applied不偷用草稿。时间文本控件保留六位小数，from含to不含；actor/target只UUID。字段、定义、常量、filters、整数token/safe integer、分页长度/唯一ID/排序/已知界、原始JSON重复/extra/缺键严格解析；错包显示未确认而非空成功或0。不得把raw body/error/未分类原值喷DOM/控制台。

390普通记录卡正常纵滚，无横向溢出，UUID可断行；清楚展示“当前单位”“缺失历史不补造”“不公开不是未记录”“结果不是当前状态/真实送达”。不新增详情/修改入口，不把旧NotificationWorkbench移植为同一页面。

## 7. 反例与验收（计划，不是通过结论）

- BE新增：V11→V12升级/root-only新权/客户禁委派、fresh撤权/禁用与单位grant、只audit最小权/无其它目录权、全局NULL单位及B隔离；四SQL/RO-RR/同谓词/分页排序/DB微秒；动作结果穷举与未知安全分类/同CASE过滤；bootstrap/UUID/null/恶意自由引用不泄露；query/framing/时间/数值严格；503/4xx审计分列；成功所有public表零写。
- FE新增：默认不请求/management gate/lazy、strict raw schema与unknown分类/引用态、draft/apply/时间精度/重置/分页/刷新/取消、完整owner晚status/body/403/401/权限与单位清理、无目录或mutation调用、390与原394/18文件原byte。旧页不可为了新页通过修改断言。
- Root冻源后唯一新完整package，真Java21/所有static/原11迁移byte不变+V12，baseline377范围外源/旧scripts不变，新H2/nativePG与真实最小管理身份Chrome（含无tenants:read指定单位、客户不可见）+原36同新包原flags兼容；原始截图逐张read后原byte归档、每job实收、只自有JVM/PG17/Node/Chrome/marker/alias/terminal清理，保既存PG18与DSH。
- 未验证的生产/厂家/真实通知/完整审计覆盖/DB防篡改/Linux/多机/WSS/HA/容量/无丢失/全面安全仍明确不宣称。

## 8. 已审注册表（61源动作+13源结果，各追加唯一UNCLASSIFIED）

以下是当前producer的有限注册表，不是DB enum、不证明这些动作在当前单位出现。**wire代码原样保留大小写/连字符**。两个definitions数组各按源code的ASCII字符升序（不用localeCompare），最后追加 `UNCLASSIFIED`，其label精确为“未分类（原值不展示）”。action/outcome两份定义总长度分别62/14、代码唯一；前后端按本表精确校验，不复用NWB八状态。

| action code | label |
|---|---|
| LOGIN | 登录 |
| CHANGE_PASSWORD | 修改密码 |
| LOGOUT | 退出登录 |
| REQUEST | 请求鉴权 |
| BUSINESS_REQUEST | 业务请求 |
| INVALID_REQUEST | 请求校验 |
| CHANNEL_UPGRADE | 信号通道握手 |
| CREATE_UNIT | 创建单位 |
| UPDATE_UNIT | 更新单位 |
| CUSTOMER-CREATE | 创建单位账号 |
| CUSTOMER-UPDATE | 更新单位账号 |
| CUSTOMER-RESET | 重置单位账号密码 |
| PLATFORM-CREATE | 创建平台账号 |
| PLATFORM-UPDATE | 更新平台账号 |
| PLATFORM-RESET | 重置平台账号密码 |
| device-register | 设备开户 |
| device-edit | 编辑设备 |
| device-migrate | 迁移设备版本 |
| product-create | 创建产品 |
| version-create | 创建产品版本 |
| version-edit | 编辑产品版本 |
| version-test | 版本合成测试 |
| version-publish | 发布产品版本 |
| source-create | 创建接入来源 |
| source-update | 更新接入来源 |
| source-rotate | 轮换来源凭据 |
| source-bind | 绑定主源 |
| source-detach | 解绑主源 |
| CAPTURE | 捕获报文 |
| CAPTURE_DUPLICATE | 记录重复报文 |
| MESSAGE_PREVIEW | 报文归一化预览 |
| PARAMETER_SET | 设置参数覆盖 |
| PARAMETER_CLEAR | 清除参数覆盖 |
| UPDATE_SETTING | 更新全局设置 |
| SIMULATE_FIRE | 模拟火警 |
| SIMULATE_RECOVERY | 模拟恢复 |
| SIMULATION_DEDUPLICATED | 模拟消息去重 |
| REVIEW_ALARM | 复核报警 |
| PROCESSING_SUBMIT | 提交模拟处理任务 |
| PROCESSING_CANCEL | 取消模拟处理任务 |
| PROCESSING_SUCCEEDED | 模拟处理成功 |
| PROCESSING_REJECTED | 模拟处理拒绝 |
| PROCESSING_RETRY | 模拟处理待重试 |
| NOTIFICATION_CONTACT_WRITE | 维护通知联系人 |
| NOTIFICATION_TEMPLATE_CREATE | 创建模拟通知模板 |
| NOTIFICATION_TEMPLATE_EDIT | 编辑模拟通知模板 |
| NOTIFICATION_RULE_WRITE | 维护模拟通知规则 |
| NOTIFICATION_QUEUED | 模拟通知排队 |
| NOTIFICATION_CANCELLED | 取消模拟通知 |
| NOTIFICATION_SIMULATION_RECONCILED | 核对不确定模拟结果 |
| NOTIFICATION_REJECTED | 模拟通知拒绝 |
| NOTIFICATION_SUPPRESSED | 模拟通知抑制 |
| NOTIFICATION_SIMULATED_SUCCESS | 模拟通知成功 |
| NOTIFICATION_SIMULATED_UNCERTAIN | 模拟通知结果不确定 |
| NOTIFICATION_RETRY_SCHEDULED | 安排模拟通知重试 |
| NOTIFICATION_SIMULATED_FAILURE | 模拟通知失败 |
| NOTIFICATION_AUTOMATION_POLICY | 设置自动模拟策略 |
| NOTIFICATION_AUTOMATION_QUEUED | 自动模拟通知排队 |
| NOTIFICATION_AUTOMATION_ALREADY_DISPATCHED | 已有模拟派发记录 |
| NOTIFICATION_AUTOMATION_SKIPPED | 跳过自动模拟通知 |
| NOTIFICATION_AUTOMATION_REJECTED | 拒绝自动模拟通知 |

| outcome code | label |
|---|---|
| SUCCESS | 操作成功 |
| DENIED | 请求拒绝 |
| SUCCEEDED | 模拟处理成功 |
| REJECTED | 已拒绝 |
| RETRY | 待重试 |
| SUCCEEDED_SIMULATION | 模拟通知成功 |
| FAILED | 模拟通知失败 |
| UNCERTAIN | 模拟结果不确定 |
| WAIT_RETRY | 等待模拟重试 |
| SUPPRESSED | 已抑制 |
| QUEUED | 已排队 |
| ALREADY_DISPATCHED | 已有派发记录 |
| SKIPPED | 已跳过 |

限定解释：原Source没有SIMULATE_NORMAL、PROCESSING_PENDING/RUNNING/EXPIRED/CANCELLED审计动作，也没有outcome CANCELLED/PENDING/RUNNING/EXPIRED/SIMULATION_RECONCILED；这类值若以后进入源表仍先归UNCLASSIFIED，不猜补。取消/人工核对的audit outcome是SUCCESS；自动/人工排队共用NOTIFICATION_QUEUED；planner另有四类真实结果。version-test的SUCCESS仅表示测试记录保存，不保证evaluation.passed。静态注册表含11项当前producer固定null-tenant动作（PLATFORM三项、product/version五项、UPDATE_SETTING、NOTIFICATION_TEMPLATE_CREATE/EDIT），本接口仍不读取其全局记录，相应筛选可为0，不能加global OR或用target反推单位。

## 9. 实际SQL预算与引用实现门

54只读源审确认：当前管理身份fresh应用SELECT为4（session/user JOIN、permissions、tenant grant COUNT、tenant enabled），reader固定4（显式size也先读defaultPageSize、DBTime、count、page），成功应用层合计8；框架/metadata/SHOW/BEGIN/SET与观察探针分列，不能冒native wire。DBTime无args的queryForObject使用普通Statement，新observer须同时观察Statement与PreparedStatement。事务入口先TransactionRequirements实际RO/RR guard。

安全UUID引用需源CHAR_LENGTH=36且完整hex-dashed格式，不能只靠UUID.fromString（可接受缩写）或regex `$`（末尾换行）；合法stored uppercase统一LOWER，源NULL与其它非UUID状态按第4节。无原始actor/target自由文绕到Java；query只能筛同一安全投影，不提供withheld内容探测器。

新审计loader可将当前store.page纳入active owner guard：navigate不增加generation，而App有退出动画，离开审计页即不允许晚status副作用，不等旧页effect cleanup。只对新页做该guard，不给全Workspace新key、不重构旧NWB/core。session.user.id与tenant.id仍旧opaque合法标识，**UUID限制不应用到登录用户/单位ID**。

## 10. 明确的唯一历史测试setup例外（授权revision2）

原AutomationMigrationTest名为V10两根权限专测，却在target9之后两次迁移到latest；正确V12增加第三个audit:read必然破其原exact两权断言。这不是改V12 selector、删断言、跳测或假装旧268已绿的理由。

Root已原byte归档并绑定唯一派生actual0，原SHA6BCE41CE...74499，派生SHA9BD9A150...DE5E3。流程必须：先正确V12且原测试不改跑focused实际RED并保日志/收job；之后仅两处literal `.dataSource(url,"sa","").load().migrate()` 加 `.target("10")`，首次target9和所有原assert/其它byte原样；新增独立AuditRecordMigrationTest验证V11→V12/root-only/其它权限及业务事实不变/latest重复幂等。除这个setup例外，原43 Java测试源及18 FE测试文件均不得更改；全部原662断言继续跑，不以预飞/旧包结果代替。

377初始观察不重写；checker授权revision2只接受该旧测试原byte或精确两token派生，其余不变。既有4产品文件亦已逐byte归档供Root最终diff审查。本合同仍非新代码/运行通过结论。
