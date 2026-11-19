# P8b2 WebSocket报警变更失效信号合同（已冻结）

当前管理端信号连接与[变更检查](alarm-changes-contract.md)使用树栏中唯一完整单位作为目标，与旧全局操作单位无关；多单位和仅位置子树不建立单单位信号连接。范围变化使旧连接owner失效，仍不自动补拉、刷新实体或扩成跨单位游标。当前范围总则见[工作区合同](ownership-model.md#14-全授权默认展示与树筛选2026-11-06)。

旧版本地出口记录：2026-10-26 00:42:55+08，旧CDBA版本曾记录538项与32个有限运行阶段通过、6原图归档及当时自有资源清理。见[历史证明](<../backend/p8b2-final-verification-proof.json>)与[验证记录](<verification.md>)。这些历史结果不是当前候选/V58的部署或验收证明。

> 本合同描述当前代码保留的显式信号能力；上方日期、CDBA证明及下方实现/运行出口均为较早版本的历史记录，不能证明当前候选或V58已部署、最终验收通过。HTTP `head_cursor` 与WS frame中的head均按当前actor可见的非模拟发布记录形成密集rank（`PER_ACTOR_VISIBLE_PUBLICATION_RANK`），不是底层全量per-tenant publication序号；完整性校验仍覆盖全部published记录，包括不可见和模拟记录。scope变更后必须丢弃旧游标/连接并重新初始化。

## 历史工作账本（按原时间保留，不作当前部署或验收依据）

- Goal：goal-3566189d-9ad0-41b8-9b90-ef21b007b8af，从P8b1 E889/196后端+285前端/30有限运行阶段继续；不是恢复已complete旧goal。用户21:17明确继续开发。
- Core：①WS只观测已发布head，不能推进人工HTTP已检查cursor/through或冒充实体事实/发布器活性；②每次推送前fresh授权，晚事件/重复心跳不能污染新范围或让dirty永远刷不清。
- Verified01：Root读原15§2.4，明确原目标WS及连接前窗口/游标隔离建议，原完整实体投影/自动重连/音频未实现；本轮只取显式WS失效信号子集。既有P8b1 BASELINE3/CHANGES4、V11捕获/发布/3表不改。
- Verified02：两owner只读评审，无编辑/build/test/服务；native browser不支持Bearer握手header，RequestSecurity对所有普通受保护GET要求Bearer，必须精确升级例外，升级后Servlet filters不再逐消息鉴权。AuthService仅原43字符raw token入口且mustChangePassword位于filter，不能漏。P8b1 panel.cursor仅H→指定WB真ACK后置ready，父dirtyEpoch/WeakMap请求起点证明可复用，不改generic hook/旧P6。
- Frozen03：Root选择B，BE最后评审明确认可：HTTP一次性同node ticket，101前fresh拒绝、无未认证WS驻留；不实现A fallback。Spring标准raw WebSocket接线（可加Boot管理版本spring-boot-starter-websocket，不引STOMP/SockJS/broker/新端口）。ticket header是短期credential，不承诺框架/网络缓冲彻底擦除；registry仅digest、不复制headers/request到attrs/日志。统一frame保下文4字段，不再重复scope。所有原196身份语义保持（CUSTOMER单位停用旧401不能为WS改成403）。
- Frozen04：FE确认仅当前owner、确切1008+AUTHORIZATION_CHANGED才触父authorityEpoch屏障，所有旧WB/bridge/Sheet微任务须旧epoch无效，仅显式一次有效WB恢复。普通1006/握手失败/ticket过期wrongnode/坏frame不能猜401或logout。lastSeen跨同基线断重连，max(HTTP C,WS W)仅去重复并不推进C/T；普通HTTP每页不重建WS。两owner只读均已结束，接显式授权后实施；当前仍无新包/动态结果，不改写旧E889证据。
- Verified05：34/35/36/38均complete。BE最终new24=HTTP9+State6+Runtime9；r3 8a5ed2d7b80245cb实际104/0（新23+旧81），后仅加同步async completion/旧finally反例，90f6c0ed8d3b4dc7 Runtime9/0，不拼成一次105或假称full220。BE先新Fake checked IOException编译错、后手工Handshake无metadata request_id致DENIED审计503，两者仅新fixture纠正；r2自有log短票/测试Bearer按明示脱敏，不改失败结果。预计full220/38XML需Root实跑。
- Verified06：FE最终6f9db67d41fe4335 build0、80focused0（new30+B1 26+P6 24）、315/315/16files实际0，full22:18:14/101.39s；先20c build0、1e build0/73/308全0，Root源复核后加强actual-start+finally completion门、加7反例，不伪称曾失败。Focused精确timing不可从当前pruned tail独立复核，不引用推算；旧285/worker/isolation/timeout/core原样。eager419501+common75650=495151；新协议/状态机均原panel lazy。
- Verified07：新Python原生WS和child交回JS（Root已接管、修CSS未引号url字面资源枚举/既有generation触public health的精确可取消permit）只有syntax0，未当功能证明。private ctx已匹配Root及child提案control:{adminToken,actor_password}；Child两次Team寻址失败后以合同FS确认，未造成应用影响。E889整包先独占原byte归档artifacts/p8b1-E8890CF963A51289.jar及baseline-proof，避免被新target同名替换而失去原包。
- Verified08：Root首次整包wrapper fc39d67b145c4b63 actual1，2行log仅cmd中pwsh不在PATH、尚未进入构建；随后Get-Command/Get-Process确认实际WindowsPowerShell5 powershell.exe，改已确认程序名及新log后a9dd8d9d51f34ff1完整包actualcollect0。Root独占proof核全新38 XML **220/0/0/0=196+new24**、FE冻结315字节身份、整个dist与所有编译Java21类/V11真实嵌入，组合535。新JAR **90CED3FDE0752430ADEC7D913D758A497FF0B37A4FC69EBCA0AB971979C8A07B / 30439404bytes**，初始JS仍495151。不能把unit/proof当native或浏览器。
- Open09：新H2首78d6bc04710c4c74与PG首fbe98a7fbb9242ef均actual1，唯一Root新fixture把http函数名覆盖stdlib http模块，首default-off POST前AttributeError，未触原生WS；只新fixture改为http_client alias。原cp936原log未覆盖，另派生UTF8副本才read，wrapper仅设置PYTHONUTF8=1用于后续日志。新a0b45f2062cc411d/b39253507f314553同90CED再次执行中；所有runtime/Chrome/旧30/cleanup仍待，CI新双存储入口及env配置仅接线、无远端/Linux运行。
- Verified10：a3两profile已走完原生前缀，却都在Chrome首WS握手前失败；a4 ee352ecb8fb44a2b静态CDP码实际为ERR_BLOCKED_BY_LOCAL_NETWORK_ACCESS_CHECKS，而非猜测401/403。安装Playwright确实支持local-network-access；只对私有context自有loopback origin显式grant、保所有HTTP/WS exact-origin gate、不加全局disable。route.fulfill地址空间因果未比较证明，不冒称已确认；frontend额外只读复核同意窄试验，无文件改动。
- Verified11：PG a5 69812680109f44f5 actualcollect0，same90CED完整native+四Chrome身份+三原图+teardown/known-secret scan均0；原生真实不读分支实际EOF_OR_RESET（不冒称观察到受控close code），另conn/HTTP继续且真返许可。PG alias实际head RO/RR/跨publisher/SQLfault、全当前public base tables成功零写也过；浏览器有显式LNA consent边界。新H2 a5 f7e9f74297f04af6在4链并行负载中健康conn提前关闭，未走到browser，不能归因SUT或编造成功。原30开始独立866ddc2d06324d44与77d7d03ddf5e49d7读取同90CED，尚未计全过。
- Open12：Root发现Runtime字段一致性独立候选：dispatch先busy CAS后start，首轮start0可被sweep读；sweep左表达式先取旧started再取sending，sample又先sending后start，能把旧DB时长误套新send期限。不能仅把H2负载关闭归因这个候选或放宽资源值；已显式reopen35，仅Runtime/newtests，要求确定模型反例→最小一致状态快照/owner修复或严格反证。不得改原196/timeout/协议/policy、full/clean/package/服务；若源修则90CED只能历史候选，Root重整包/新最终全链另计，不能拼旧已过出口冒充新包。
- Verified13：35重新complete rev6。旧source确定模型8dc21c08b0934e5d actual1，选3 **3/3F/0E/0S**（23:40:30+08/1m21），分别busy发布、phase旧start混读、旧expiry-owner在completion后替代，都是目标断言失败不是fixture超时；修后045bb7ef4d034e96 actual0，**Runtime12/0**（23:44:56+08/1m07），149main/39test重编0。Source仅Runtime：Connection极短monitor同组phase/start/busy+dispatch owner，sweep在同锁先不可逆closing/firstreason fence再锁外close，finish仅本owner；锁内无JDBC/JSON/send/execute/registry。Rootread105行复核，仍不归因H2 native那次关闭。新总27=9+6+12，预计full223/38XML，FE315仍冻；新完整包及最终32待Root。
- Verified14：候选90CED已独占归档artifacts/p8b2-candidate-90CED3FDE0752430.jar，不以旧packet证明新source。旧H2十八866ddc2d06324d44 actual0；PG十二77d7d03ddf5e49d7仍持有旧Jar，Root等其结束才clean/package。链接验证新工具首因旧cp936 anchor解码失败，仅换byte换行计数；后发现5个历史dead链接（FE4旧hash文件、BE1旧target日志），Root只文档改为历史名称/hash+已清除说明，不造下载；当前22文档423本地链接/line锚全部存在。source/dist/tests没因此改动。
- Verified15：旧候选PG十二77d7d03ddf5e49d7亦actualcollect0，原30全部同90CED保browser flags通过，只是候选历史。Root等全部持Jar进程结束后唯一重整包c8939f8f60aa421b actual0，2026-10-26 00:00:33+08结束/7m36；38新XML **223/0/0/0=196+new27**。独占新proof逐byte核43dist/318编译Java21类/V11及依赖；最终候选 **CDBAA4D3F67E1402F7749A402897DA06B5E02A1D2E176CD86BD135EC30B5407A / 30440123bytes**，冻结FE315不变、组合538，初始JS495151。新原生driver只增加safe close静态分类诊断及真实5000byte text/binary对4096的1009断言，不削原断言/timeout/resource值；.gitignore仅排本地baseline JAR。
- Open16：同新CDBA的双新完整native/browser现由ab30907da4e1466b和23c26a2453c248f0单独运行，未与旧30并行，真实oversize/healthy和新互斥状态需此轮证明。新最终旧30仍待这两个结束后同hash重跑，旧90CED出口不可替代；总清理/最终6PNG也尚待，不能提前complete。
- Verified17：最终CDBA PG23c26a2453c248f0 actual0，含新增真实5000byte text/binary→1009，全部native/四Chrome/cleanup与known-secret0；不读client实际仍EOF_OR_RESET。H2 ab30907da4e1466b前缀全部过、到重启readiness却收到literal `GET /api/health HTTP/1.1`作为响应首行而退出1：app-3 log仅到Tomcat initialized、未started，shared OwnedApp.start原BaseException已stop，无活进程泄漏。与pre-listener ephemeral self-connect一致但不冒称因果确认。只在新driver暂时gating原health call直到本owned log精确port started，保原90s/角色/assertions，不吞live malformed response、不改shared旧fixture/SUT。H2新0c4cb4d4782543ca actual0，全部native/四Chrome、1009/真实EOF_OR_RESET+healthy/cleanup/secret0。
- Verified18：两final 6原PNG均Root逐张实际read；新archive helper只读原图/报告核sameCDBA/SHA/size/PNG尺寸，独占copy6并复核byte完全相同，docs/screenshots/p8b2-original-proof.json actual0；无裁拼/预览替原图、无凭据/草稿截图。CDBA最终原H2十八007a3fa0570f4a57、PG十二fa91a99e3f4c4c89已开始，原调用/PG browser flags不变；现在不能拿旧90CED三十pass替这轮，最终32/总资源核验仍pending。
- Verified19：最终CDBA原H218的007a3fa0570f4a57、PG12的fa91a99e3f4c4c89均actualcollect0，分别00:33:58、00:42:16+08完成；新两profile1+1连原18+12共32。新final-proof只用90CED receipts作原命令inventory对照，32 exit/hash/日志都验新CDBA，PG除identity均原--browser。Root marker glob postmaster.pid/init-password.tmp均空，terminal_list空，相关jobs全collect；00:42:55只读CIM项目Jar/node/PG17与automation Chrome0、SUBST项目alias0，既存PG18 svc4904/main7520与10-16创建日仍在，没广杀/改服务。final-verification-proof实际0：538、32、6原PNG、cleanup true；原包hash/30440123不变。source/dist/tests不再改变，仅文档收口；不是生产/真实渠道或全面安全认证。
- Next：本地P8b2子切片已完成；没有自动实施自动重连/实体流/音频或业务默认值。后续需求另行确认，所有历史Open已由后续Verified关闭或明确留为非因果/外部验收边界。
- Scope：backend owner只backend/**，frontend owner只frontend/**，Lead独占本合同/scripts/CI/rootdocs/最终target-dist clean package；冻结后显式授权，完整包只Root运行。

## 1. 范围

- WS路径沿原方案：/api/management/alerts、/api/customer/alerts。仅站内head采样提示；无alarm/entity payload、事件回放、自动HTTP补拉/自动重连、统计patch、弹窗/声音、领取、真实厂商/通知/下行。不承诺无漏报、实时值守、发布器心跳、容量、跨机HA、断电恢复或混版本完整覆盖。
- 默认/打开检查面板/初始化/普通HTTP每页都0自动WS。用户在真实H→WB ACK就绪后显式连接；断开、断网、到期、server重启之后需用户手动重连。原人工检查路径与原cursor/T不受网络失败干扰。
- P8b1 mandatory capture始终写的成本及read/publish off语义不变。信号enabled默认false，且需既有read enabled、all/api角色及ready；publish flag不是本机或集群在线证明。不同JVM publisher经PG持久head可被API采样，不依赖本机内存广播。

## 2. 已冻结：一次性ticket

- POST /api/{audience}/alarm-change-channel，JSON空对象、无query；原Bearer/首次改密/当前alarm:read/单位grant与启用状态全部fresh，客户不能伪造X-Tenant-Id；只内存短期能力、不写业务/schema/audit success/幂等；拒绝沿既有DENIED处理。Cache-Control:no-store。
- Origin必须精确配置规范HTTP(S) origin allowlist，bootstrap与Upgrade都拒缺失/null/多个/非规范；不信任Host/Forwarded推导，Origin不是非browser的身份凭据。ticket绑定origin/tenant/audience/session tokenHash，内存只存ticket digest与内部会话引用，43位CSPRNG原票仅一次响应。
- bootstrap闭集5字段：v（原int1）、scope（tenant_id/audience/session的P8b1二级会话指纹）、ticket、connect_timeout_ms、silence_timeout_ms。数值为受限整数技术资源，不是业务设置。前端构造同页面origin ws/wss路径，不接服务端任意URL。
- 握手Sec-WebSocket-Protocol请求两个值：alarm-changes.v1、ticket.<opaque>，仅协商/echo alarm-changes.v1。敏感一次性票不进URL/query/日志/close reason/DOM/state/storage，原Bearer也不进WS或票值；不承诺开发者工具看不到握手请求头。
- 只为精确两路径、真实Upgrade GET开RequestSecurity最窄例外；普通GET和任何既有接口仍原鉴权。升级入口自身守ready/role/flag/Origin/raw path/无query/严格协议、原子消耗一次票并fresh重新验证当前会话、首次改密、audience/alarm:read/grant/单位。无票/已用/过期/跨node/错audience/origin不能upgrade。
- tickets pending+claimed+active共用全进程/每session配额，claim与onOpen中间不能释放permit造空窗；失败/过期/close/stop回收，stale cleanup不得释放被新代数占用的permit。仅same API node，负载均衡需同node/粘性，此片不提供跨节点ticket。

## 3. 发送与资源

- 每轮只读投影当前actor可见真实发布记录的head（无可见记录时为null/0；不造head/不COUNT pending/不查业务alarm-device-trace表）；底层发布完整性仍独立覆盖全部published记录。fresh身份权限必读identity表，不能声称所有SQL仅feed表。head读事务关闭后，候选发送前fresh reauth，拒绝旧session/撤权限/grant/单位停用/首次改密状态；授权检查与网络发送不是原子瞬时撤销，禁止承诺撤权提交后零在途帧。
- 发送统一文本frame闭集4字段：v原int1、type=ALARM_CHANGE_SIGNAL、head_cursor=当前actor可见真实发布记录的密集rank编码（语义 `PER_ACTOR_VISIBLE_PUBLICATION_RANK`）、semantics={kind:INVALIDATION_ONLY,publisher_liveness:UNCONFIRMED,delivery:PER_CONNECTION_SAMPLE}。每次可重复同head，也可跨多位置跳跃；不是连续event流。不发送应用错误body或敏感权限原因。
- 第一帧是fresh授权后的head，然后持续按配置采样同一持久head，故不会因先查再挂内存监听造成永久漏窗；首帧覆盖的仅是已发布窗口，不是尚未发布业务。onopen/网络101不是授权成功，须首个有效frame才显示通道已建立。
- 入站无应用消息协议；文本/二进制/再次认证等一律关闭，不实现订阅切换；标准WS ping/pong/close仍由容器处理。慢发送/任务/队列/连接总数必须有上界，不在业务事务内网络发送、不创建无界thread/任务；无吞吐SLA。
- 技术配置冻结：enabled=false、allowed_origins空（启用须显式）、poll_ms=1000[50..60000]、connect_timeout_ms=10000[1000..60000]、silence_timeout_ms=15000[1000..180000]、max_connections=32[1..512]、max_per_session=2[1..32]、send_timeout_ms=1000[50..10000]、worker_threads=2[1..16]、max_message_bytes=4096[1024..65536]；max_per_session<=总cap，silence>poll+send。启动校验、bootstrap下发两个客户端deadline，不散落业务默认，统一APP_ALARM_CHANGE_WS_*与app.alarm-change-ws-*，可用独立新policy类不扩大旧构造器义务。
- 定时器不执行JDBC/同步send/同步close；有界worker池+有界队列、每连接最多一个DB/发送inflight，拥塞可丢旧采样（不是丢持久事件），不能累计frame。选Tomcat/Jakarta asyncRemote.sendText+setSendTimeout与completion单inflight，明确native profile、非该native实现fail closed；不能把ConcurrentWebSocketSessionDecorator或Future.cancel当实际阻塞send中断保证。必要同步close也离开timer并由容器timeout/有界执行限制；关闭中仍占permit，不释放后再等待关闭。不持DB/RR/identity或业务锁跨网络。Root需有限不读client真实检查，不用mock冒充native慢client出口。
- 每tick的head snapshot完成/释放DB事务后、发送前再fresh身份范围，不能用旧排队Identity。会话失效/权限或grant撤销/单位或账号停用/首次改密等当前授权不成立时，已建立通道仅close 1008 + 精确静态reason AUTHORIZATION_CHANGED；不得回显细节。过期票/错误node/握手失败/协议错误/SQL故障/拥塞等不得使用这个reason，防误将网络问题当撤权；允许1000正常、1008 PROTOCOL_INVALID、1009超限、1011 CHANNEL_UNAVAILABLE等静态原因。已进发送buffer不可撤回，不承诺撤权commit后零在途帧。

## 4. 前端生命周期

- 独立socket owner/epoch与baselineEpoch；普通HTTP check不得因cursor变化重建连接，WS事件从不写C/T或原data-alarm-change-position/through。H初始化/重建先关闭旧socket，H→本次nonce WB真ACK后才允许显式连。
- 同baseline observation high=max(有效HTTP C,已接WS W)只供去重复，不是持久已检查位置；相同heartbeat/旧HTTP补页不重复dirtyEpoch++，观察新发布位置才onHint。此观察高位在同基线手动断开/重连间保留，scope/session/new baseline销毁。任何网络/关闭/重复都不能清父dirty。
- 首帧校验下界用发起该连接时的anchor C，不是之后live C，允许合法晚首帧低于已推进live C且不制造新dirty；连接内其后要求同epoch非递减但不要求连续。null0到首UUID只采用一次，第二不同epochfail closed，不能因HTTP C仍null0反复换stream。
- capture token/generation/audience/tenant/permissions identity，bootstrap及所有socket callbacks先instance+epoch+owner核验；解绑/失效epoch先于close，旧late响应/open/message/error/close不能盖新通道/新登录状态或发送旧票。票只局部交给构造器，不持久记录；bootstrap401只通过当前owner原API机制处理，普通网络close不等于401。
- 正常hint不动草稿/选中/详情/筛选/统计，不自动GET。网络/坏frame/epoch问题显示通道未确认并保留人工检查位置。原生browser在upgrade HTTP401/403通常只有1006，不能猜状态，不自动HTTP探测或logout；票过期/错误node也是未确认而不是撤权。
- 仅当前实例/连接owner捕获authorityEpoch匹配的、1008+精确AUTHORIZATION_CHANGED触父安全屏障：同步block ref/authorityEpoch++、取消bridge并清本检查流/观察位、卸载selected/detail Sheet并清成功notice，所有旧WB行/卡/确认as_of/摘要都gate blocked+请求实际开始时proof.authorityEpoch。旧HTTP或Sheet onChanged/onReviewed微任务不能重画/清dirty/恢复或再封禁新owner；不能用callbacks.current把旧close重新贴新epoch。撤权时清安全缓存优先于草稿保留，但不承诺撤销已发业务事务；普通hint绝不走此路。
- blocked时用原useResource.enabled=false暂停/abort，不只改key触自动新GET；禁旧行/筛选/初始化/连接，仅显示专用显式“重新读取当前工作台确认权限”。恢复用唯一recovery nonce/current authorityEpoch/query/generation+owner；只临时许可这一真实WB请求，现validator成功且proof全匹配才解除屏障；失败/坏200/403不露旧cache，真实HTTP401沿原机制。成功不复活Sheet/草稿/ticket/C/T/WS，之后仍需显式H→WB ACK才可再连；不改generic hook/P6。
- 独立strict raw bootstrap/frame parser复用P8b1 coordinate（重复键/闭集/整数原字面/精确Long/作用域/绑定），不改旧shared/P6 decoder或原285断言/2workers/isolation/timeout。新模块处于原显式lazy里，初始JS要连同eager common计<500000。
- 开发Vite /api proxy已仅补ws:true；仍须显式匹配Origin，不把changeOrigin当认证。Root正式验收同origin完整JAR，无替换DSH 3080服务。

## 5. 反证与出口（本地有限验证已闭合）

- 伪造/缺失/多个Origin、普通HTTP/异常Upgrade/错subprotocol/票回放并发/过期/跨scope/audience/session与node、取消bootstrap、配额ticket→claim→active与过期回收、首次改密/撤权/停用/登出/过期fresh拒绝、只读head/零业务写、publisher另JVM、server重启/SQL异常/慢client与shutdown资源回收。
- 默认0新HTTP/WS、未真WB ACK不连、初始已发布窗口、新采样跳位/重复head/HB、HTTP固定T不推进/不被重启、late HTTP vs WS dedup、manual reconnect观察高位、auth close缓存屏障、late old scope/old login/旧close、坏raw/empty/binary/unknown实体字段/大Long/null0两epoch、复核输入与390真实Chrome。
- Root唯一最终package完整196+285+增量；新两存储动态+原30阶段（原PG除identity都含--browser不可漏），原始PNG逐张看/原byte hash/43资源及新zip核、相关jobs收齐、自有资源清理，保留10-16既存PG服务。不把JUnit或源码review冒充真实upgrade/浏览器/生产/全面安全验收。

## 实施接线补记（不改wire）

- FE源实物shared request对401仅token串比较；新bootstrap用独立lazy专用fetch（不改shared core），响应后先完整captured owner+attempt/socketEpoch guard，只有当前真正HTTP401才走原notice/clear，其余安全固定error、不解析记录error票据body。credentials omit/cache no-store/redirect error/AbortSignal；管理固定tenant头/客户不带；此保证只新bootstrap，不能顺手声称旧所有HTTP升级同强度。
- task35/36已get/claim并正式授权，仅各backend/frontend；Root task38并行写两个新夹具，task37仅等source冻结后整包。普通child c74a461f仅新alarm-channel-browser-smoke.mjs，Root仅新alarm-channel-smoke.py；parent两次send UUID/prefix都报active teammate not found，是通信寻址限制非应用失败，不再反复重试；本节供child只读接线。
- Browser private stdin ctx确认：沿旧url/tenant_id/other_tenant_id/unguarded_tenant_id/platform/scope_platform/customer/reviewer/producer/artifact_directory/jar_sha256；四actor仅username/password，producer仅token/device_id/tenant_id且Node-only；新增control:{adminToken,actor_password} Node-only（private ctx按child已读实物命名，不是API wire），Root/child均已read真实GET /management/units与PUT /management/units/{id}的name/enabled/expected_revision/actor_password+Idempotency-Key，不能伪造接口。停单位只撤CUSTOMER sessions，平台token可复用，authority矩阵先platform，再客户重新登录。child确认的原生WS constructor精确permit门卫可以用（仍new Native WebSocket，不mock/route），受控晚真实close事件delivery gate须显式标注，不伪造frame/关闭原因。原票加入in-memory secrets、禁止dump任何Sec-WebSocket-Protocol/raw bootstrap对象；FE selectors源码落盘后由Root接管核对，不擅自称已实测。
- FE刚报实物落盘（首build仍未结束，不当成功）：按钮「连接变更信号」「断开变更信号」「手动重连变更信号」；p[data-alarm-channel-status]是空标记+中文，首validframe后含「信号通道已建立」；authloss unmount panel/Sheet，[data-alarm-authority-blocked]与「重新读取当前工作台确认权限」，成功不自动开panel/H/WB/WS。其它旧HTTP attrs同前。Root确认上述private ctx control:{adminToken,actor_password}，脚本先platform authority toggle、后customer新登录。
