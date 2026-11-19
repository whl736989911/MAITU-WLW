# P3b1：单位/设备离线阈值覆盖与生效追溯

## 边界与继承

承接P1..P5a。补可消费的分层参数，不承诺完整参数系统。此次只开放offline_threshold_minutes的GLOBAL→TENANT→DEVICE继承（设备优先单位优先全局）；产品层和更多参数后续另定，不展示假生效层。default_page_size仍GLOBAL_ONLY，继续由现有分页消费者使用。电量scale/offset/min/max仍为不可变产品版本字段，不借本接口改旧模板/历史。

全局值/上下界/单位来自现有numeric_settings与集中Limits，不能复制一份全局真相；offline阈值1..525600分钟，当前开发默认2880，不宣称已批准生产阈值。全局原GET/PUT settings API及页面保留P1 CAS/审计合同（旧写无二次密码/幂等，不假称已迁移）；本轮不改该兼容行为。新覆盖均需敏感确认、幂等与历史。

配置仅影响列表/设备读取/summary的连接状态推导，调整后下次读取生效；无消息始终UNACTIVATED，不因阈值变大变ONLINE。边界last_message_at >= evaluated_at - minutes为ONLINE，严格更早OFFLINE。不得改设备physical_state、last_message_at、电量、revision、integration_state，报警/事件/P5任务/人工状态均不改，不建离线通知/扫描。P5a与捕获/预览原合同保持。

## 目录和权限

新增parameters:read、parameters:write，仅PLATFORM，V7只补受保护bootstrap-admin PLATFORM根账户。read可读集中定义、当前单位最小设备选择、覆盖/历史；write还须read，当前DB授权/单位启用/首次改密/管理受众重查，actor_password、Idempotency-Key、reason、expected_revision。该独立权限只管理读时连接策略，不写设备元数据，不要求device:write/device:read等无关目录权限；不扩大为客户设置。客户仍只能device:read读自有设备的安全有效策略。

全局GET /api/management/parameter-definitions -> {items:Definition[]}，parameters:read，无需单位；不要因页面选了单位错误地传header给全局API。Definition={key,name,unit,min,max,value_type:"INTEGER",allowed_scopes:["GLOBAL","TENANT","DEVICE"]或["GLOBAL"],consumer,effect:"QUERY_ONLY",global_value,global_revision}，仅真实已消费的两键，未知键404。

其余management作用域都用当前有效X-Tenant-Id；tenant_id不接受请求body伪造。TENANT路径scope_id必须当前单位id，DEVICE必须当前单位真实存在设备，未知/跨单位404。worker-only仍503。

- GET /api/management/parameter-targets?page=1&keyword= -> Page<{id,name}>，parameters:read，只当前单位最小设备目录；不要求其它读目录权限，实际分页/字面搜索。
- GET /api/management/parameter-values/{scope}/{scope_id}/{key} -> PolicyView。
- PUT 同路径 {value:JSON整数,expected_revision:非负整数,reason:必填,actor_password} -> {policy:PolicyView,change:Change}。
- POST 同路径/clear {expected_revision,reason,actor_password} -> {policy,change}。
- GET 同路径/history?page=1 -> Page<Change>。

scope仅TENANT/DEVICE，GLOBAL写仍旧settings；非法scope400，合法但不支持的key/scope组合409 PARAMETER_SCOPE_UNSUPPORTED。新value/revision严格整数token，不接受字符串、bool、null、小数、科学计数法或越界；请求未知/重复键400。reason文本沿用集中长度/非法编码边界。

PolicyView={key,scope,scope_id,tenant_id,has_override,value:null|integer,revision,effective:Effective,chain:ChainEntry[],consumer:"QUERY_CONNECTIVITY_ONLY"}。
Effective={key:"offline_threshold_minutes",value,unit:"分钟",source_scope:"GLOBAL"|"TENANT"|"DEVICE",source_id:null|string,source_revision,evaluated_at:time}。
ChainEntry={scope,scope_id:null|string,has_override,value:null|integer,revision,is_effective:boolean}；顺序GLOBAL、TENANT、可选DEVICE。GLOBAL始终has_override=true，使用现有全局实际值/revision。不存在覆盖revision=0；清除后留tombstone、has_override=false/value=null、revision不回0。response.effective与chain同一DB读取快照，显示来源不是硬编码默认。
Change={id,key,scope,scope_id,tenant_id,action:"SET"|"CLEAR",previous_value:null|integer,value:null|integer,revision_before,revision_after,actor_id,reason,created_at}。只追加本轮以后真实覆盖变化；旧数据没有历史则空，不补造旧覆盖。全局基础项仍旧审计，不塞进覆盖历史冒充完整全局变更日志。

同key/body成功重试返回同一个不可变change、policy重新按当前父级/覆盖投影，不再执行SET/CLEAR、不重复历史/审计；变业务参数同key409。每次重试仍重验当前权限、密码、单位/目标归属。父级变化不改子级revision；CLEAR表示回归提交时最新父级，不承诺表单先前看到的父值。旧key重放不是回滚后来的覆盖。

## 持久化/模块/锁

追加V7，V1..V6不改。configuration持有覆盖/不可变changes；唯一(tenant_id,scope,scope_id,key)，清除不物理删，revision每次SET/CLEAR+1，创建使用expected_revision=0，数据库约束防并发/无效值。重复CLEAR新key409 NO_OVERRIDE。保留tombstone防清除再创建ABA，CAS冲突409必须刷新重开，不自行+1。变更和审计/幂等账本同事务，失败全回滚。

新命令锁序管理协调→actor→tenant→必要device公开只读加锁端口→覆盖行。使用shared只读目标端口（由device实现，不依赖DeviceQueryService）避免configuration↔device包/bean循环；或同等无反向依赖方案。域不能跨写表。device原有QueryService可依赖configuration公开读取策略/SQL投影端口；不是configuration直接改设备/报警。

## 实际消费者和一致性

所有管理/客户设备列表、设备单读和summary必须使用同一有效规则；不能页面显示覆盖但后台仍全局。DeviceRow增加offline_policy:Effective。设备列表每页一次时间快照，策略值/来源/revision从同一SQL快照解析；summary按每台设备有效阈值聚合，不能用单全局cutoff或对所有设备N+1读取。优先configuration提供固定只读JOIN/投影/时间比较片段的公开端口，device组合d别名/参数化SQL；PG可interval、H2可DATEADD，差异集中且两库实测，禁止按分钟round导致边界误判。不扫描整库到Java内存算summary，不缓存过期值。

设备单读保留既有row-lock语义，不误锁策略表或反序：H2/PG JOIN SELECT FOR UPDATE需仅锁device（可先锁device再非锁一次策略读取），不能让查询无意锁上全局/其它租户覆盖行。P5 completion已有device锁必须兼容，策略改变不改device revision/P5身份代数，不能制造假业务拒绝/恢复。

新增 GET /api/management/devices/{id}/effective-parameters 与 /api/customer/devices/{id}/effective-parameters，device:read/正常单位范围 -> {device_id,items:[Effective],consumer:"QUERY_CONNECTIVITY_ONLY"}。不要求parameters:read，不泄露覆盖history/actor/reason/其它单位；customer不接受tenant头。服务端仍是权威，evaluated_at不是设备上报时间。

## 前端

新增管理“参数覆盖”页，选单位后仅parameters:read可读；先选TENANT或DEVICE，设备用专门最小target目录，不假借完整device权限。查看当前覆盖、有效值、来源、各层revision、空历史/已清除；定义从API，不硬编码业务范围。SET/CLEAR填写理由后二次当前密码、同key重输重试、409刷新重开；切单位/登出/关闭销毁表单与密码，晚响应不串scope。只清除覆盖，不删除历史或伪造硬件下发。

设备表（管理/客户）展示实际offline_policy值/来源，未知策略不画默认或0；必要单读详情用effective-parameters安全接口。不展示客户配置按钮/覆盖历史。旧全局设置页面补清楚全局默认与分层规则提示，不改旧操作流程。页面没有离线告警已发送、扫描已启用或设备恢复暗示。

## 验证出口

保留79后端+90前端基线。新增V7升级/root-only权限/旧0与NULL/无覆盖继承、TENANT与DEVICE覆盖优先/清除回归最新父级、scope非法/越权/客户只读、严格数值与body、当前授权/密码/重放/ABA、不可变历史/审计事务回滚、源/设备物理事实全不变、列表/summary/客户/effective端点同规则、精确截止边界/无消息UNACTIVATED、无N+1与summary不入内存扫描。Lead完整包H2及PG17实测，PG用自有libpq连接将合成设备last_message_at设为固定过去时间以检查阈值实际重分类（不把该夹具写当硬件上报），变更前后其余事实/任务/事件/报警不变；Chrome走实际SET/继承/清除/历史/错误密码与单位切换。P1-P5a原生PG/Chrome回归不退。
