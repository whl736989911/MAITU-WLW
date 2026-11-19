# P7b1 新模拟火警自动通知合同（已冻结）

## 工作账本

- Goal：把新模拟FIRE创建事务接到P7a持久通知，不接真实电话/短信，不补旧，不复活旧策略意图，保留全部135+184旧功能。
- Core：①FIRE/trace/自动决策同事务，采集点而非可回填occurred_at决定新旧；②明确启用的policy epoch及封存授权/规则图，变化只终止旧意图，不能重路由。
- Verified：✓01 已读当前AlarmEventService统一P1/P5创建口、P7a Commands/Runner、身份锁与架构规则。✓02 P1当前仅actor/session/tenant，没有coord；须在任何旧锁之前通过identity公开入口补coord。✓03 P5完成已有coord，RR等待锁前snapshot可陈旧，不能仅MAX(seq)/时间比较激活。
- Verified：✓04 Backend与独立只读反例审均认为同步SPI可行；已收口必装唯一participant、外RR拒绝、callback零新锁/零crypto、封存批准整图、不重基准、existing-dispatch优先与rollback-only边界。
- Verified：✓05 backend新16+旧34定向50/50 exit0；frontend先207后真实shell补8，最新215/215 exit0、主495.17kB+scope1.69kB lazy，双方source正式冻结。✓06 6593预飞PG API全链0，含capture/planner专属nonrollback marker与完整rollback digest、API-only/坏key暂停/两worker/人工优先/代数/真实due；H2default-off0。
- Verified：✓07 Root真Chrome发现并修应用shell无目录权无法选scope，新增明确未核验ID上下文，不扩权/新API；8D32预飞H2 Chrome完整0，含new-read实际表单/未知403恢复/zero旧目录/真实FIRE+SYSTEM2attempt/stop/晚scope响应/客户/390稳定严格pixel。首个390单中间帧受入场过渡影响，等待fonts+≤2s稳定布局即0，无CSS/宽度阈值/测试timeout改动。
- Verified：✓08 Root完整build `pwsh-2902f414585f4573` 已collect exit0：独立215/215（48.78s）→重build495.17kB→clean/嵌入→完整151/151、0F/E/S，08:37:06 BUILD SUCCESS、Maven3m52。最终JAR SHA256 `CFA188C121D27BA91067801CF7BF7927A4ED85FC1072DC45DE0BF3D5BD3E58D9`，29,272,208bytes。首个wrapper ec23因PowerShell 2>&1+Stop把普通npm notice提成NativeCommandError，不是test失败且没开始Maven；已查子进程结束/原8D包不变，直接原生调用后全0。所有以上旧job已收，不重复collect。
- Verified：✓09 最终CFA H2十五阶段链 `pwsh-fc4c3bb7f89e4aee` 已collect exit0，前后hash未变：自动Chrome/defaultoff/P1/P2及独立Chrome/P3/P4a Chrome和关闭/P3b1 Chrome/P5 Chrome和关闭/P6a1/P6a2/P7a Chrome全部0。独立grep30份clean JUnit headers合计151、0F/E/S，RootVitest12文件215与495.17kB实输出也重查。✓10 CFA原生PG自动+Chrome第一阶段0，report .runtime/notification-automation-844d320074984f4693cda6aa243e34fe已读；三截图已逐张读查并原字节复制到docs/screenshots，原PNGhash在verification，不用downscale JPEG摘要。
- Verified：✓11 最终CFA原生PG九阶段总链 `pwsh-b7b5f33e4c84427e` 已collect exit0，新自动+旧通知/P5/P6a1/P6a2/P2/P3/P4a/P3b1（除P2均Chrome）全部0，前后CFA hash不变。08:53:22精确应用JAR/自有browser脚本进程0、runtime postmaster.pid无、SUBST空、terminal空；未知瞬时非应用Node未被kill。所有Root job均已收，别再重复collect任何已完成job。
- Open：本P7b1合同的必验项已完成；真实厂家/电话短信/设备下发、Linux/跨机器/5万设备容量/生产灾备仍未实现或未验，不在本次成功范围，不拿fake/两本机JVM冒充生产可靠性。
- Ship：后端151+前端215（原135+184保留）、完整CFA包/H2十五与PG九/新旧Chrome/390/故障/cleanup、Rootdocs及三张原生PG合成截图均收齐。Backend task20与Frontend task21(rev6)完成并补交接；source/target/dist冻结且无剩余成员工作。当前goal可标complete；下一切片按路线与用户真实接入资料另行明确，不自动启用外部渠道。

## 0. 范围

- 增加新模拟FIRE创建→同事务自动模拟决策/意图→worker生成P7a dispatch/records→既有purefake worker执行的闭环。无真实厂商/短信/语音SDK/HTTP/回执/硬件操作，无领取。
- 默认全局关闭、单位未配置/关闭；启用/重配必须明确选择现有规则ID/revision和四种模拟场景之一，不默认第一规则或SUCCESS，不新增无消费者业务数字。
- 不给V10之前报警补意图。GET、页面打开、保存/启用/停用/改策略都不能给旧报警补发，意图无重放/重排接口。
- 每报警永久一个P7a SIMULATION dispatch继续成立；人工与自动竞争同一业务唯一键。已存在人工dispatch时，意图只关联，不复制record/attempt。
- 原P7a SafeRecord/Attempt/History、人工排队/取消/核对wire及客户权限合同保留；共享文案须从全局“不监听新报警”澄清为“当前手动入口不自动排队；另有显式启用的单位自动模拟策略”。

## 1. 原子采集与激活边界（必装同步SPI）

- alarm公开immutable `AlarmCreated` DTO及同步participant SPI。**单个必需participant注入，缺失或多个启动失败**，不得用可空List/ConditionalOnProperty/worker-role控制capture注册。AlarmEventService.fire在写alarm+FIRE_CREATED trace后于原事务直接同步调用participant；notification实现只依赖JDBC/config/封存plan查询，不注入AlarmEventService/Commands，不在低层导入notification。
- participant必须MANDATORY同事务、无async/afterCommit/网络/crypto/key验证/第二actor锁。异常不能吞，意图写失败应回滚alarm/trace/P1事件或P5业务及最终CAS，commit后崩溃不靠内存消息恢复。V10只新增 `notification_automation_policies / notification_automation_changes / notification_automation_intents` 三owned表，changes同时承载immutable epoch sealed plan，intent保存当时sealed副本；现dispatch新增origin(MANUAL/AUTOMATIC_SIMULATION)+automation_intent_id/epoch，仅内部列，旧行默认manual、旧唯一键和JSON不改。
- P1通过identity公开coord入口在actor/session/tenant/device之前先持coord至commit；P5保持既有coord先锁序。相关写事务明确READ_COMMITTED，并以实际连接隔离/既有Tx校验拒绝外层RR（REQUIRED/MANDATORY注解本身不保证换隔离）；拒绝必须整体回滚且不留alarm孤儿。不能把写入包进旧RR读事务，更不能在fire已持device后回头取coord/policy/审批人锁。
- 在已持coord的采集阶段只用无锁SELECT读取当前policy/epoch，不补FOR UPDATE。所有policy/规则/模板/联系人写均coord先行；审批者/租户停启仍由worker按当前代数复验，采集阶段不持第二用户锁。
- 每个新明确模拟FIRE都必须唯一决策/意图行，no-policy/disabled/source feature-off记录terminal SKIPPED，不成为未来可消费待发；新非模拟FIRE不捕获。capture只无锁compare封存rule/template/contact当前代数，若创建前cfg已失效→SKIPPED/POLICY_NOT_EFFECTIVE；不论如何绝不替换封存代数，planner仍复验。只有创建点已明确启用且运行开关有效的epoch可PENDING；API-only允许PENDING持久等待独立worker。
- policy启用/任何重配/停用都生成新单调revision与epoch，封存当时rule/template/contact完整代数图和授权actor/tenant代数。原key重放不移动epoch；没有按occurred_at/created_at过滤、MAX(seq)或浏览器时间证明新旧。
- **选择policy保存时封存整图**：rule/template/contact revision、当时safe展示、业务数字、authorizerRev/tenantRev均来自该次真实批准；新FIRE capture只复制这份sealed plan，当前值仅compare，不能借P7a人工queue取最新把旧批准重基准。联系人/模板任一代数变化后，必须显式重配/确认policy才授权新的图；旧policy key replay不刷新图或epoch。
- epoch切换之后，旧PENDING intent及自动dispatch未完成record按旧epoch被拒绝/抑制，绝不改绑当前规则/接收人；已完成模拟结果保留。manual dispatch不受自动策略停启误伤。

## 2. 授权与停止

- 新PLATFORM独立 `notification-automation:read/write`，只补保护根账户，旧普通用户/客户不增权；客户新端点不映射404，伪tenant头仍403。
- GET cap/policy/intents仅新read，不暗取旧配置/报警/设备/通知目录。意图只给安全摘要，无号码/遮罩/联系人身份/密文/MAC/模板正文/审批者/原因/服务商参数。
- 启用/重配：新read+write + P7a队列四权（notifications:read/simulate, alarm:read, notification-config:read）全六；当前tenant grant/enabled、actor密码、CAS/Idempotency-Key/reason重验。有效三开关与contact storage ready才可新启用/重配（503否则），不允许在未具条件时给UI假就绪。选择当前enabled rule及精确revision，不允许用过期rule授权。原key replay重验当前权限/PW但不重新批准/移动epoch，不因后来缺key假造新操作。
- 停用：仅新read+write、当前密码/CAS/key/reason，feature关闭/缺key/旧rule失效不妨碍止损；body明确清空rule/scenario，重启用须再次明确选择。
- 自动worker用独立authorization owner快照代数/当下完整六权，不保留用户session/PW，不调用人工queue(password)冒充人现场提交。自动历史/audit为SYSTEM；当时策略审批者仅是授权依据，不是现场人、报警复核人。

## 3. 消费与纯模拟

- intent无外部副作用/无发送lease。本地automation flag-off或缺/错key节点**暂停，不扫描/ack**，避免坏节点毒化健康节点工作。健康节点先无锁locate，coord后按可信tenant/alarm先检查已有dispatch；**已存在dispatch优先**于policy/actor/tenant/graph/HANDLED失配，只关联ALREADY_DISPATCHED/created_count=0，绝不修改或增发旧manual/auto记录。
- 无既有dispatch才按coord→审批actor→tenant→policy→规则图固定排序→device→alarm→最后intent lock/SKIP LOCKED复验当前epoch/资格/fake标记/状态/CAS。policy/config预期失配用typed结果或noRollbackFor纯读fence，不得catch已经把外层标rollback-only的ApiError导致永远PENDING；SQL/codec/写失败仍必须逃逸回滚。
- 同一事务复用P7a底层dispatch写入器（不是人工HTTP命令）展开封存plan及actual due，并最终更新intent+SYSTEM audit；故障/取消/最后CAS失败不留半批记录。DB异常回滚后可重新纯计算，不制造provider UNCERTAIN。level due继续以**实际dispatch入队DB时间**为基准，不从可回填occurred_at或API等待期间自行推算已执行/已升级。
- 自动record遇本机automation flagoff或缺key也暂停，候选过滤不能让它挡住旧manual record。单位policy改代/撤权等在健康worker持久REJECTED旧auto记录；manual保持P7a原fence/flag行为。
- actor/tenant/rule/template/contact/policy代数变化或禁用→REJECTED，不自动重路由/重试；alarm已HANDLED且尚未建dispatch→SKIPPED，已有未执行records由P7a SUPPRESSED，物理NORMAL仍不等人工完成。
- 手工先有dispatch→ALREADY_DISPATCHED，关联原ID且created_count=0；自动先建dispatch后人工新key保持既有409。QUEUED只表示已生成通知队列，不表示attempt/成功/送达。
- 自动dispatch携带policy epoch及授权basis；实际P7a record执行也再查当前policy/授权，旧auto record不能因策略停启复活，manual不查自动policy。
- `APP_SIMULATED_NOTIFICATION_AUTOMATION_ENABLED=false`默认关闭，须与P7a simulated notifications和simulation开关共同启用；这是**事件来源实例capture开关**，不是跨JVM一致DB总闸，持久单位policy才是总闸。intent worker、自动record worker、旧P7a manual worker、本机role与单位enabled是不同事实，cap只说本机，不证明集群活跃。复用现有`APP_NOTIFICATION_POLL_MS`与`APP_NOTIFICATION_BATCH_SIZE`的有界部署策略，单线程可各做最多batch项意图与record，不增业务数字或无消费者参数。

## 4. 冻结wire（唯一实现依据）

四个端点仅PLATFORM management；policy/intents用X-Tenant-Id，cap为global且不带tenant头。customer路径不映射404。所有new read仅新automation:read，无隐含旧目录权。

- `GET /api/management/notification-automation-capabilities` → `{mode:"SIMULATION",feature_enabled:boolean,intent_worker_enabled:boolean,record_worker_enabled:boolean,contact_storage_ready:boolean,scenarios:["SUCCESS","REJECTED","RETRY_THEN_SUCCESS","UNCERTAIN"],intent_statuses:["PENDING","QUEUED","ALREADY_DISPATCHED","SKIPPED","REJECTED"],limits:{reason_max_length:1000}}`。feature是本机三开关AND；两worker bool只本机三开关+role能力，record_worker是自动record能力，不改旧P7a manual flag；key readiness独立，不能推断集群已运行。
- `GET /api/management/notification-automation-policy` → `{configured:boolean,enabled:boolean,revision:number,epoch:string|null,rule_id:string|null,rule_name:string|null,rule_revision:number|null,scenario:string|null,changed_at:offset_datetime|null}`。首次由server明确configured=false/enabled=false/revision=0，其余null；0是absence/CAS技术哨兵，不是持久policy。首次写rev1，之后单调，epoch不透明UUID。rule_name/revision是封存快照，不join新目录名。disabled configured=true仍有真实rev/epoch/time，但rule_id/name/revision/scenario全部null。
- `PUT /api/management/notification-automation-policy` body严格 `{enabled:boolean,expected_revision:integer,rule_id:string|null,expected_rule_revision:integer|null,scenario:string|null,reason:string,actor_password:string}` + `Idempotency-Key`。enabled=true三绑定必填、精确rule revision；enabled=false三绑定全null。200响应同当前PolicyDTO；原key成功重放只返回当前真实policy读取，不把旧批准snapshot假装当前、不改变epoch/图（UI仍独立GET重读）。未知/重复字段与非整数、过界、坏null组合400；当前PW403不注销会话，CAS409重开。
- `GET /api/management/notification-automation-intents?page=1&size=...&keyword=...&status=...` → Page `{items,total,page,size}`。keyword字面匹配intent/alarm/device快照等安全字段，不搜号码/联系人；status只五状态。行严格 `{id,alarm_id,device_id,device_name,address,policy_revision,epoch,rule_name,scenario,captured_at,processed_at,status,error_code,dispatch_id,created_count}`。device_name/address/rule_name/epoch均当时安全快照。policy_revision/epoch/rule_name/scenario可null（未配置/禁用）；captured_at始终真实DB时间，processed_at在PENDING为null、终态有值。created_count为实际本意图新建record数：PENDING=0、QUEUED=2..100且dispatch非null、ALREADY_DISPATCHED=0且关联旧dispatch、SKIPPED/REJECTED=0且无dispatch。error_code为稳定安全码或null，不含私有内容；未知原因码不推断成功。
- capture终态SKIPPED码 `NOT_OPTED_IN/POLICY_DISABLED/AUTOMATION_DISABLED/POLICY_NOT_EFFECTIVE`；规划失配REJECTED码至少 `POLICY_CHANGED/AUTHORIZATION_CHANGED/TENANT_CHANGED/RULE_CHANGED/TEMPLATE_CHANGED/CONTACT_CHANGED/SIMULATION_TARGET_REQUIRED`；无dispatch且已HANDLED→SKIPPED/ALARM_HANDLED。ALREADY_DISPATCHED与QUEUED的error_code=null。DB异常不转任何这些终态、不借UNCERTAIN，原Tx回滚。

### 界面与行为

- 一个lazy管理页“自动模拟通知”：当前策略独立刷新+实际意图分页，customer零新端点。new-read-only不能偷取旧cap/rules/contacts/alarms或跳转读取P7a记录；具旧notifications:read才可明确跳转另页。
- 真实集成发现无tenants:read的平台旧shell没有单位入口，补**显式单位ID上下文**lazy小表单，不新增API/隐含目录权限/授权数据。标注“未核验/由服务端逐请求核验”，不伪造真实单位名或已授权；输入未知/无grant单位仍403且清旧scope数据、保有效会话。有tenants:read保持旧目录select，客户无跨单位控件；关闭/对象/登录/scope代数清表单草稿。
- 四端点、null组合、真实默认页大小、字段与状态均以上述唯一wire为准；不把404/坏metadata伪造关闭，不泄审批身份/原因，不join新名字冒充旧快照。未知状态/坏时间/分页矛盾显示未确认。
- 敏感PW/reason/草稿/晚响应在close/object/scope/登出/撤权generation清理；403保会话清PW退确认，409刷新重开，丢响应原key不盲新建。主入口<500kB，184旧测试保留，默认Vitest2workers/隔离/超时不动，无新增SDK。

## 5. 出口

- 135旧后端+184旧前端全部保留在latest schema（仅历史迁移测试target历史）；新intent写SQL故障与P5最后CAS故障回滚到alarm/trace/event/task/intent；原key/语义去重与manual/auto并发。
- 真PG API-only→两个worker、跨进程持久、创建/启用或停用竞态、source flag关闭后再开不补、policy/actor/tenant/config停启代数、HANDLED/NORMAL分离；无真实IO、无未确认默认数字。
- Chrome真实启用/停用/rearm/当前值重读、无默认rule/scenario、纯new-read零旧目录、意图与record状态不混/客户无新路由、scope/敏感清理、390无页面溢出、旧P7a和全部旧链同完整JAR回归。
- 完整嵌入dist包/hash/3张实测图/文档与自有进程收齐清理；不把本片当生产自动通知或真实送达。
