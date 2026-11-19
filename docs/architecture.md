# 第一阶段架构与部署边界

## 最新约束

本工程采用模块化单体，仅支持 Ubuntu/Linux。运行依赖为 Java 21 和数据库；前端构建后是静态文件，不需 Node 常驻。生产使用 PostgreSQL 17，H2 仅用于开发和自动化测试。当前要求见[部署手册](ubuntu-deployment.md)，下文旧包与旧宿主结果仅供追溯。

不强制Redis、EMQX、MinIO、Docker、WSL2。若真实设备直接连接MQTT，则需要经验证的MQTT接入服务；厂商云HTTP推送和轮询不依赖EMQX。当前模拟或通用HTTP捕获都不代表任何厂家协议已支持。

文件存储通过明确的存储接口演进；首阶段本地目录只适合单机，后续拆机前必须引入共享文件服务或对象存储，不能将本地路径直接发给另一机器。

## P8b3有限客户端恢复边界（本地有限验证完成）

B1持久发布与B2票/permit/同node native head采样均保持原模型；新增服务端策略bean只在GET读取集中环境值，无新表、线程、timer、worker、依赖或feed业务SQL。成功走原fresh身份/显式scope，无重hash刷新/授票/许可/写。可省Origin的普通同源GET不改原POST/Upgrade必须显式canonical Origin的规则。

客户端supervisor随原panel懒加载，只由当前手动首valid后的明确用户选择创建一次性计划；已取得B2 connect_timeout_ms限定policy的status/body期限。click T0钉window，成功/心跳不重置N或窗口。重试从当前同基线HTTP C拿anchor，每次重新POST单用票，并保每阶段owner/AE/baseline/attempt/monotonic期限围栏。末次成功后保原healthy通道但终止retry计划，后续head/AUTH仍走原B2。stop/expiry只撤未确认候选与自己的timer/fetch；不补拉、改统计/实体/复核或存储opt-in。

四项环境开关默认关闭。客户端配置快照不是服务端授权、租约或撤销信号；实际连接权限和并发限制仍由服务端控制。HTTP 在响应头之前和之后采用不同重连判断，均有截止时间。测试中的透明 HTTP/1 relay 仅覆盖 EOF、连接重置和浏览器 1006，不证明 WSS、TLS、负载均衡、HA 或容量。详见[重连合同](alarm-change-reconnect-contract.md)。

## 目标模块

| 模块 | 所有权 | 可拆分前置条件 |
|---|---|---|
| 身份权限 | 用户、会话、租户访问、权限 | 所有进程使用相同的持久会话/授权真相 |
| 配置与产品 | 参数目录、配置值、产品及解析版本 | 发布版本快照可跨进程读取 |
| 设备 | 平台身份、所属租户、连接与物理状态 | 主源映射和更新并发规则统一 |
| 接入 | 通道、厂商适配器、原始消息 | 接收确认前持久化消息与任务 |
| 处理 | 解析、归一化、事件、报警 | 持久任务、幂等、乱序与恢复关联 |
| 任务 | 通知、命令、画像、导出 | 租约、attempt、回执和不确定状态 |
| 查询呈现 | 双端API、页面、后续WebSocket | 数据范围一致，WS跨进程投递和补拉 |

P1/P2实现身份、配置、设备与模拟报警，P3增加独立product领域及设备版本绑定，不是全模块已实现。product只依赖配置/身份/公共层；device通过PublishedVersionCatalog只读不可变快照，不直接改产品表；product不依赖device/alarm。领域表写入由各自服务持有，同一事务通过公开服务组合，架构测试禁止反向依赖与跨域写表。P4a独立ingestion领域持有来源/绑定/原报文/预览，identity提供TenantFence、device提供只读加锁目录、product提供已发布版本归一化接口；下层不反依赖ingestion。捕获/人工预览保持NOT_APPLIED。P5a新增独立processing领域持有任务/attempt，公开CapturedMessageCatalog、ExecutionAuthorization、SimulatedMessageApplication等端口组合同一事务；device持有task应用幂等并通过alarm端口只恢复指定模拟报警。只有人工明确提交的模拟任务会消费，真实来源仍不自动推进。

P7a新增notification叶子域，持有加密联系人/key-guard、SIMULATION不可变正文模板、单位规则/接收人、dispatch/record/attempt/history。通过device→alarm公开只读加锁端口确认模拟身份与当前人工状态，不写旧业务表；新权限单独委派，客户仅安全记录投影。为纯fake选择单record短事务：coord→actor→tenant→配置→device→alarm→record顺序锁、纯计算与attempt/result/history/audit原子提交，无网络/文件/脚本副作用或外呼lease。当前全局安全coord串行是吞吐限制；不能把这个函数直接替换为真实provider联网，生产外呼必须另外实现事务外调用、持久发送尝试、租约fence和UNCERTAIN对账。原P5真实DB租约fencing不改变。

历史通知自动化在报警事务中仅通过同步 `AlarmCreatedParticipant` 捕获通知意图，不在持锁事务里发送。授权代数、事件范围和停用状态影响计划是否可执行；计划与人工发送互不阻塞。模拟设计只能说明本地事务行为，不能证明真实外呼或跨机器可靠性。

## 运行角色演进

目标角色：all、api、ingest、worker。首批工程只允许真正实现的运行模式生效；不能因health显示一个字符串就声称已支持多机任务消费。

- 一台：all + PostgreSQL + 静态前端。
- 两台：应用和工作进程一台，PostgreSQL一台。
- 四台：API；接入与处理；任务；PostgreSQL。
- 六台：进一步分离接入和处理，基础服务按需独立。

这是部署组合而非高可用保证；数据库、接入源、文件仍可能单点。P5a已在同一Windows宿主的原生PG17上实际运行api+两个worker JVM，覆盖过期owner晚回写失败、强停恢复、独立worker产生模拟报警由API读取；不是跨机器/真实源/跨机推送和文件共享的生产出口。all/api可显式排队，all/worker需双模拟开关才消费，ingest仍没有实现消费者。

## 数据与事务原则

- 客户租户由有效会话确定，平台跨租户必须显式选择并被授权。
- aud与用户类型绑定，不允许登录参数提升身份。
- ID以字符串出API，避免JavaScript大整数精度损失。
- 会话令牌仅传输一次，数据库只保存摘要，浏览器仅内存保存。
- 模拟消息按稳定消息ID去重，复核按乐观版本和幂等请求处理。
- 人工处置不修改物理恢复；物理恢复不自动完成处置；新设备未上报为UNKNOWN。
- 版本发布不可变，设备钉住具体版本；迁移只改绑定并留历史，不改物理事实或历史报警快照。
- 声明式解析用于合成样例、捕获版本人工预览及明确模拟任务，不执行通用脚本/网络；有输入字节/深度/数值上界和实际chunked流计数。独立worker JVM已有固定JSON异步任务，但不是任意脚本的OS资源沙箱。
- 任务领取只锁job短事务，解析在事务外；完成按协调→actor→tenant→source→device→job→alarm，当前三代数、绑定/版本/设备revision和观测时间重验后原子推进；末次DB完整时钟owner/token CAS失败回滚全部业务。PG用clock_timestamp，H2实际statement时钟，持久时间向下微秒避免round-up瞬时不eligible。
- P5a仅明确模拟源/设备，源/单位/账号disable→enable也不能复活旧任务；RECOVERY只关联明确同设备模拟报警，其它活动FIRE不解除。未知/畸形/失配不写业务。NUMERIC(64,36)电量不经double/Integer截断，null不抹掉旧观测。
- 通用捕获在鉴权后持久化原始UTF8和捕获版本，稳定消息身份跨到达时间去重；预览不推进设备/报警。原文/原始事件需独立权限，旧幂等结果按当前权限重投影。
- 接入凭据至少256bit随机，只首次响应传明文、数据库仅摘要；丢首次响应须显式轮换，不从幂等账本找回。接收锁顺序tenant→source→device，与停用/绑定协调。
- 配置数值有类型、单位、边界、作用域和审计；默认值为集中可覆盖的初始值，而不是散落页面常量。
- 协议字段宽度、HTTP状态码、算法参数不属于任意可改业务数值；突破安全限制需要评审。

## P3b1离线参数读模型

configuration持有新增覆盖/不可变变化记录，设备通过shared ParameterTargets独立只读目录与目标锁端口供配置命令核对，配置不反向依赖DeviceQueryService。固定OfflinePolicySql只读投影由device列表/单读/summary共用；设备只组合d别名和参数绑定，不跨写配置。锁命令沿协调→actor→tenant→目标device→覆盖，与P5不倒序；读取device锁时先单表FOR UPDATE再非锁JOIN，不误锁全局或其它设备策略。

优先级DEVICE>TENANT>GLOBAL。一条SQL快照内解析值/来源/revision，精确>=截止为ONLINE、NULL时间仍UNACTIVATED；summary在SQL按每设备有效值聚合。H2测1与52条列表均3次准备查询、summary2次且含SUM，PG双API实测无内存陈旧值。evaluated_at是查询评估时间，不是设备新上报；配置不写设备/报警/事件/任务。

全局旧settings保留原CAS/审计兼容；新覆盖采用二次密码/当前授权、敏感幂等、单调revision/tombstone及不可变历史，清除不删历史。V7只补根账户新权限，客户device:read只见安全有效策略；产品层和硬件下发仍未实现。合同见[参数覆盖](<parameter-contract.md>)。

## P6a1报警业务轨迹

alarm领域持有alarm_trace_entries，四种既有alarm写路径创建/复核/指定模拟恢复/旧按设备恢复在原事务追加；没有独立成功日志事务或跨领域表写。旧device恢复按alarm id排序取锁逐条留先后态；复核只actor/tenant→alarm不反向索device。P5最终owner/token/DB时钟CAS失败仍回滚业务及轨迹。人工actor快照通过identity已锁当前身份公开边界读取，非请求body或未来用户名。

V8只将旧报警当前状态记作MIGRATION_BASELINE（空先态/发生时刻/身份，真实迁移记录时间），不猜原始操作。历史查询RR事务包含当前record+SQL聚合覆盖度+限定页，唯一revision与动作判据区分从创建/迁移以来/不完整。当前详情与历史快照分开显示；旧review返回的缓存snapshot不能充当当前物理态。

alarm:history与alarm:read组合授权；只有根账户默认补新权限，客户需显式委派。当前详情不携备注；历史管理视图才有actor_id/name快照，客户直接不同投影omit这两key而非null掩饰，无raw/凭据/租约。只有业务事务写入，没有补写/编辑/删除轨迹接口；不代表通知发件箱、领取锁或完整P6状态机已实现。合同见[报警轨迹](<alarm-trace-contract.md>)。

## P6a2报警查询工作台

用户明确不做领取/独占处理，有既有复核权限者直接提交、原CAS/幂等不变；10分钟选择不作为无消费者配置。新增management/customer alarm-workbench只需alarm:read，完整报警页消费新同口径API，dashboard compact和原所有API保留。

AlarmWorkbenchFilter集中验证/规范query，字面SQL LOWER+LIKE转义，enum AND与occurred_at显式offset微秒[from,to)；只新端点拒绝未知/重复参数。RR内先取既有默认页大小及实际DB as_of，再同一where/bindings的单aggregate与LIMIT页，固定4查询，没有整库Java汇总/额外COUNT或N+1。统计是真实全部匹配页的当前轴/当前结论，不猜创建日期、今日窗口、故障或通知；查询as_of不替代设备/报警时刻。P6a2本身不增加schema、领取锁、新权限或写API（后续V9只属于P7a通知域，旧功能继续在latest schema验证）。

前端草稿/已应用条件与当前单位generation隔离，显式北京时间输入不依浏览器zone；响应时间/filters/定义/指标/页行验证后才显示已确认口径，不把错误/损坏metadata当0。后端统计不是客户端按页重算，已知页计数和确定过滤只能用于拒绝自相矛盾响应，不能替代全量值。合同见[工作台](<alarm-workbench-contract.md>)。

## P8a1最小设备读取与精确关联报警

新增双端device:read详情只投影现DeviceRow十七安全字段及实际离线有效策略；所存product_name/model/版本是开户或最近显式迁移保存的元信息，不追latest。public只读RR入口验证active只读事务与JDBC物理隔离，外RC或可写RR拒绝，不凭注解假升级；新DB clock UTC微秒as_of与同JOIN策略求值只两查询，不改旧Clock列表/effective。as_of是评估而非提交水位，H2虚拟时钟本身不承诺锚定首次业务表快照。

关联报警需device:read+alarm:read，经shared ParameterTargets由独立DeviceParameterTargets核对父归属，alarm不直接依赖device/产生反向循环。父归属、默认条数、DB时间、同一tenant+device+用户filter的SQL聚合与页均在同一实际只读RR，固定五查询；不按名称/EUI匹配、不锁行或在Java取全库。新device_id范围不塞进旧六filters，旧全单位工作台wire/权限/四查询保留。未知或别单位设备404而不是空列表，拒绝仍记录原安全DENIED；成功GET不写业务/审计/幂等。

前端详情与 API 解码独立按需加载。版本和修订等字段必须为安全整数，数值保留原始小数精度。设备当前事实与历史告警快照分别显示；报警须独立权限。设备、单位、权限变化时清理关联内容与草稿，不允许迟到响应恢复旧选择。详见[设备工作台合同](device-workbench-contract.md)。

历史隔离验收覆盖 H2、PostgreSQL 的只读快照和浏览器窄屏；不代表当前构建、生产并发或真实厂商验收。

## P8a2显式设备筛选工作台

新增双端device-workbench只需device:read，不借summary/报警/产品/参数目录权限。旧DeviceQueryService的Clock方法和写调用原样；新public方法验证active只读/JDBC RR，默认条数→一次DB UTC微秒as_of→聚合→页共四业务SQL。固定旧select()/row()十七字段mapper，外层派生q过滤computed_connectivity，tenant在内层；一个不可变SQL计划及同as_of绑定供两次SELECT，非N+1/Java全库、写锁或提交水位。查询预算不等于全量扫描恒时或容量验收。

五个AND条件只当前保存name/EUI/address字面、连接、物理、接入及三态模拟标记。false须显式boolean/绑定参数，present空值400，不宽松coerce。九桶在全部匹配页分别按连接三分区、物理三分区、模拟二分区，不能把未上报算离线、UNKNOWN算正常、FIRE算人工未处理，或non_simulated当已接厂家。固定版本保存元数据不追latest。成功读零业务/审计/幂等写；拒绝安全DENIED另验。

高级列表由用户主动打开，按需加载并取消旧查询；切换时清除条件、记录选择及过时响应。高级与基础表使用同一组读取权限和参数校验，编辑、迁移和历史仍走基础入口。详见[设备工作台合同](device-workbench-contract.md)。

## P8b1持久报警变更提示边界

alarm自有V11三表：独立publisher coord、tenant head和outbox。AlarmTraceWriter原MANDATORY事务插入四类新APPLICATION轨迹后同Tx必装capture，异常向外传播；不碰业务fence/head或跨域表，不回填历史，不提供TTL/清理。read/publish默认off仍capture，成本和存储故障耦合不可隐藏。两个worker仅feed coord→候选/head→outbox/CAS短事务writable RC，无业务表查询/锁/网络；publication sequence只表示每单位发布次序，不是UUID/time/revision或业务提交顺序。

双端新GET fresh alarm:read与固定tenant/session/audience约束；规范base64url坐标不是授权。只读/JDBC RR内先真实默认条数表读，再DB UTC微秒求值、单SQL head+pending，CHANGES加稳定C<seq<=T页；业务SQL预算3/4不含既有身份鉴权。null0的显式T0只归一epoch不扩大位置，缺页/epoch/越界安全失败。pending为已提交未发布任务，0不证明发布器活性；整库回退同epoch也不总能检测。

前端显式H→专属nonce/query/generation的旧工作台validator兼容可见成功ACK→手动范围分页，零默认暗读/轮询/patch。父dirtyEpoch独立游标、跨panel关闭保留，老WB ACK不能抹新提示；scope/session/权限变化销毁范围，复核草稿与原直接CAS/幂等不变。新协议独立raw/Long/canonical检查；旧shared/P6 decoder不被借机重写或升级词法保证。

连接中止和客户端在 COMMIT 前后消失是不同的故障条件；未知提交结果不能标记为确定回滚。历史 PostgreSQL 故障仪表仅用于隔离测试，数据摘要不证明角色、函数、序列或数据库断电可靠性。详见[报警变更合同](alarm-changes-contract.md)。

## P8b2显式head信号边界（本地有限验证完成）

在P8b1持久head之上另建raw WebSocket，不使用STOMP/SockJS/Redis广播。POST受既有Bearer/tenant/audience/alarm:read/首次改密及显式规范Origin围栏；独立43字符随机票仅registry摘要，绑定内部会话/范围/Origin，同node单用。pending→claimed→active→closing始终计同一个总/会话许可；claim后重新授权再101，失败/超期不可复活。仅精确两条真实Upgrade GET跳过原Bearer filter，普通HTTP保持原逻辑，无alarm→identity反向环。

head读取仅feed-head单SELECT、实际RO/JDBC RR，无pending COUNT/业务表/造head；事务返回释放后再fresh用户/会话/密码/audience/权限/grant/unit再候选发送。统一4字段frame只有失效head和三个严格语义，不携实体/数量/积压/重复scope。timer不做JDBC或同步网络，DB/必要close用有限worker与2×cap派生队列，native Tomcat Jakarta asyncRemote timeout/completion单inflight。8数值集中配置、关系启动校验；不能把Future.cancel/decorator当阻塞发送已终止的证明。

新WS owner/连接epoch与HTTP operation/C/T独立；同baseline max(有效HTTP C,有效WS W)只去重，普通HB/信号不抹草稿、不自动读或重连。只有当前正确协议精确授权关闭才建authorityEpoch屏障，独立dirty并封闭旧行/卡/摘要/Sheet/旧回调，显式恢复actual-start+本次finally completed与原WB validator合取。所有network/协议/票据未确认不冒充撤权。source新增guarded bootstrap fetch只在当前完整owner和attempt成立时处理401/body，原共享request不借机改变。

同域名不等于同 API 节点。进程本地信号票需要单 API 或粘性路由；持久 head 可跨发布进程读取，但不会共享信号票。反代不应记录敏感 subprotocol 请求头。浏览器/代理缓冲可能在撤权后仍有在途帧。详见[信号合同](alarm-change-channel-contract.md)，不作为生产 WSS、代理或容量保证。

## 当前验证边界

Windows是当前开发宿主。Java和Maven使用项目内便携工具，不修改系统安装。Docker客户端存在但引擎未运行，因此不能把容器测试当作已完成。

历史测试不作为当前源码、上线或物理烟感设备验收凭证。发布前须在当前 Ubuntu 构建上验证应用和浏览器路径，在独立 PostgreSQL 上演练备份、升级、恢复；真实厂商投递或收费通知在单独授权的环境验收。
