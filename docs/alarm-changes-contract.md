# P8b1 报警变更持久补拉基础合同（已冻结）
> 本文保留历史合同或验收记录；原始报告、截图、JAR及未公开合同不随公开源码发布，文中历史结论不代表当前版本验收。

当前实现的HTTP游标语义为 `PER_ACTOR_VISIBLE_PUBLICATION_RANK`：每个已授权读者只看其范围内的真实报警变更，并按其中可见记录密集编号；序号不是底层每单位全量发布位置。完整性检查仍针对底层全部历史published记录，含该读者不可见及模拟记录。改变单位/位置可见范围会改变可见排名，旧游标须重建，不能跨范围沿用。本文后续长账本与旧验收出口均是对应历史版本记录，不证明V58已部署或当前候选完成最终验证。

## 历史工作账本（按原时间保留，不作当前验收依据）

- Goal：在P8a2最终CBE/175后端+259前端及28有限运行阶段基线上，完成报警变更的持久发布与双端显式检查/刷新提示基础，不假冒WS、完整报警事件流或断线无漏报。当前goal-a602309a-4630-4168-af03-c88e1271f553，旧352已完成不恢复。
- Core：①提交后的连续publication位置不能越过晚提交记录；②每次fresh鉴权，提示不是业务事实/报警计数，不干扰人的复核草稿。
- Verified01：原15号§2.4明确补拉为WS上线前需冻结建议、先边界再快照、防漏窗、连续坐标、游标不得跨用户；13号§3.3要求业务/轨迹/发件箱同Tx。本片只实现基础子集，没有WS/弹窗/声音/实体patch/无漏报保证。
- Verified02：真实唯一AlarmTraceWriter.append为MANDATORY，AuthService.Identity含tokenHash。FIRE_CREATED在trace后再执行P7 capture；P5最终租约CAS又在trace之后，故新捕获与这两类晚失败必须全回滚。Review没有Creation CoordinationFence，不新增该锁或RC要求。
- Verified03：backend/frontend已只读评审，无编辑/构建/服务。BE提出同域3表而非重复outbox+delivery队列，capture无任何publication业务共享锁，publisher只feed-owned锁；旧Java32是枚举旧表而非schema总数断言，新全schema证明要独立补。旧Ingestion直接SQL删trace/tenant，故feed安全值不挂业务FK，不反向查/锁业务表。FE确认H→指定工作台成功ACK→显式检查；固定目标T；scan与needsRefresh分开，关闭面板不抹除父级旧视图已知待刷新。
- Decisions：选alarm同域required capture helper，不为同域引入不必要SPI；3表、始终捕获/默认关闭发布与读取；无head返回null epoch/0，first发布建UUID；游标fingerprint绑定session但编码不是签名授权；前端桥局部nonce，不改generic hook/旧P6 decoder。
- Verified04/冻结：两owner已确认单GET/11top/三query/固定T及nonce桥可行；Root选择BE更窄BASELINE3/CHANGES4业务SQL（state单条合并head/pending），并封口null0显式T0只归一epoch不升位置、末页清activeThrough、取消桥入口及父级dirty epoch。允许旧ArchitectureTest仅将新3表追加alarm ownership，原断言/规则不改弱；其余旧175/259断言/超时/worker不动。
- Open：尚未新JAR/动态证明，CBE仅上一基线。Root合同/scripts/CI/rootdocs独占，明确task31/32授权后各域实现，source冻结前Root不build/启动服务。始终capture会增加每trace持久写和存储失败耦合，默认关闭不代表业务无侵入。
- Verified05：task31/32均get/claim实施。BE已落V11三表alarm_change_publisher_coord/heads/outbox与required capture/RC publisher/strict reader，compile cc5f4c实际collect0；首focused24只有新wiring无DataSource夹具先失败，18新集成/迁移/config与旧Arch已0，现只修new夹具重跑；拟增21、预计full196待Root实际。FE首build df827e actual0，新lazy8.27k；bundler自动main419.50+eager common75.65≈495.15k，不伪称主链减76k，新tests未完成，259+N待验。两者均未full/clean/package/起服务。
- Verified06：Root只写新Python夹具alarm-changes-smoke.py，py_compile前台exit0不当功能验收；3表/native fault/late私有低UUID intent模型/实际capture失败rollback/role DBclock RR/四身份payload草拟，未运行新JAR。Node子agent90b4b878独占单新browser脚本，基础--check0但整脚本/真实Chrome未验；已实际读panel/bridge selectors。临时child UUID不能被Team send_message寻址，双方工具报not found仅通信方式问题，无文件/应用影响；child仍按只读实物继续。Root保最终接管修改权。
- Verified07：task31/32均complete/source/tests/dist冻结。BE final23bd4=91/0，新21+旧70，预计full196现已由Root实际35新XML验证196/0F/E/S；FE最终9762106=build0、50focused（新26+旧P6 24）与285/285/15files（17:11:59/83.48s）实际collect0，原259不改。此前284全绿后owner复核发现空200/204在shared request无raw不decode，新API先拒undefined并新增1test，既有shared/P6未改。FE四job历史在own README。
- Verified08：Root首package881e9333 exit1，仅`*>&1|Tee`令PowerShell把npm notice转换NativeCommandError，未到Maven；检查无相同checkout构建子进程，HTML/main字节仍冻结值。预期首log并未生成，检查Get-Item也因此1（非应用失败）；未掩盖。改原生cmd重定向包装、不改原package脚本/生产后5378a5 actualcollect0；完整包日志与原byte证明：JAR E8890CF963A51289F4696E504924189EA2F3BCA7FADCA7A3539DD2F1F96DB6B5 /29,334,628 bytes；35 XML=196/0，新V11+6 Java21 class及整个dist全部zip-byte一致，初始419501+75650(common)=495151<500000。旧CBE不冒用。Node stdin已收窄只用户名/密码，并用小写SHA适配闭集。
- Open09：新H2+Chrome job100bab9c与PG+Chrome040f204f正跑，当前无出口不标通过。RootPython含真实FIRE/review/P5+P1recover捕获、defaultoff磁盘积压重开、私有低UUID/1970待发布输入晚提交（不伪称业务trace）、实际capture故障全表rollback、双publisher故障/重试、private LongMAX/跨tenant批回滚、deferred commit前精确kill/已观察commit后kill-reopen非精确丢ACK边界、alias DBclock实际RO/RR门及全schema只读；H2file WRITE_DELAY=0仅测试。Node已停写交回578行，Root分段读实际selectors/网络ticket/私有输入保护/真实held，而非模拟WB ACK。
- Verified10：首新H2 100bab9c退出1在Chrome前，Root夹具把全局default_page_size设2触旧Limits.min5的400；仅新夹具改合法5，真实分页改6 FIRE的5+1，整Node共12真FIRE，原setting约束/产品不变。首PG040f204f退出1到真实capture故障：Root错期望500，旧ApiError正确503 STORAGE_UNAVAILABLE；仅新断言改精确503+code，保留全schema rollback检验，不改产品。cbfff536新H2+真实Chrome最终actualcollect0，四身份/真实H→双heldWB ACK/fixed5+1+下一窗口/坏200不推进/dirty跨close及晚WB/晚A切B/复核草稿/UTC390 Sheet均0；原PNG1440×1080和390×844已Root逐张read_image，未裁切拼图。
- Open11：第二PG1929c65退出1在private commit gate仍持锁时等被杀client的PG backend消失超时。Root/BE只读review确认这是夹具错误：server已收到COMMIT的锁等待不保证检测client EOF，纯client kill不保证rollback；未放宽旧超时/改产品。仅新夹具分两路：按private gate+COMMIT+user/db/backend_start摘要精确取PID并在仍匹配时终止该连接+ownedJVM、证明回滚重开；另client kill后先解gate，再允许且仅允许原子commit/rollback两态、记录实际观察分支并重开exact once（不声称实跑两态或精确lostACK指令）。新6eb5024d在跑，Root source/current判定仍待出口。BE其余long/rr只读反例review仍待回，不重开31或改源。
- Open12：同E889旧H2完整17阶段 ca3ea492正在跑；新H2已过，但PG及旧PG11/最终清理未收齐。CI新增H2与WindowsPG HTTP入口，未验远端或Linux；session history storage unavailable已实际报错，不假称查到历史命令，旧运行列表由现脚本CLI与已结基线核对。
- Verified13：6eb新PG actualcollect0、实际unknown COMMIT分支COMMITTED，另态没冒称实跑。BE只读反例review结束（未改/未运行）；发现is_called后普通MVCC旧值不能独证Tx已结束，Root只新夹具补取得coord事务结束屏障+持锁单SELECT两head/两outbox，正常别名ROLE不称继承SUPERUSER、异常局部清理不凭finally声称实证；passed后移至ExitStack及已知秘密log检查后。最终c49a5646 PG与2ee7fe76 H2再次actualcollect0，原E889/产品冻结不变，报告teardown_completed/known_secret_server_log_scan_passed为true；新2最终脚本均实跑，重复不累计。4张最终原图均Root逐张read_image后exclusive原byte归档与SHA见截图证明，不是normalized attachment或拼图。
- Verified14：ca3ea492旧H2全部17阶段actualcollect0；原旧PG11的9743ba81进行中，最后读取至第7完成/第8启动（不是最终状态），不能提前标30完成。Root README/实施/架构/路线及验证新章已更新候选状态，最终只余旧PG实际出口与总清理/hash/收口。
- Verified15/修正：9743旧PG11 API-only实际collect0。但job_list此时返回旧CBE原精确命令，Root确认旧PG除identity均有--browser；此次Root漏flag，不能拿API-only冒充保留原28运行。已保正确identity通过，新的b5e6985e按原参数跑其余10个PG+Chrome完整阶段，目标仍2新+17旧H2+11原PG=30；不改产品、旧断言/超时/worker，不重复加总。
- Closed16：b5e6985e原PG+Chrome **10/10 actualcollect0**，加此前正确identity=原PG11，旧H2 ca3=17，新最终c49PG/2eeH2=2，**同E889全部30有限阶段实际0**；误漏browser的API-only不冒充原出口，复跑不重复计数。所有相关jobs已collect、31/32及额外只读review已结束。18:34:05+08总收尾证明：自有Java/Node/Playwright/PG候选0、无postmaster.pid/自有SUBST/持久terminal，包/43资源/4原图hash不变。初过宽“整机PG=0”检查退出1，后只读识别10个10-16早已存在、pg_ctl4904→PG7520的postgresql-x64-18服务进程，原样保留，未连接或停止；不误清用户服务。文档181既有local链接核0后，历史已替换CBE hashed JS仅保摘要、不伪造下载，最终新增收尾链接指向已存在证明。上面Open/Next皆为当时历史，最新出口已完成，随后以工具将task33/goal标记complete；不是WS/生产/远端CI/Linux/容量/灾备/全面安全审计。

## 0. 范围

- 不含设备元信息/到钟离线、参数时间窗口、通知结果、源事件时间或因果顺序、删除事件及更多处置阶段。游标只按授权actor可见真实发布记录形成密集rank，不是源时间排序；不是实时值守、自动重连/补拉、声音、WS/SSE或实体合并。
- 历史P7模拟处理和P7模拟通知仅为退役前记录，不是当前接口/操作建议；当前真实消息捕获与通知配置分别以本代码版本合同为准。原175/259测试记录仅代表当时版本。
- 不新加客户业务参数；size复用现全局default_page_size与Pagination。publisher周期/批量只集中受限部署技术项，不当作通知SLA。

## 1. 持久模型与提交后发布

- 仅三张alarm-owned新表：singleton publisher coordination、tenant stream head、safe outbox。outbox也是发布提示存储，不复制第二队列/实体载荷。
- outbox包含唯一id/delivery UUID（可直接使用该trace UUID一一映射，不用它排序或当cursor）、tenant引用值、recorded_at与nullable publication_sequence；不含alarm/device ID、状态、姓名地址、actor、remark、原文、电话或secret，不回trace_id字段。安全值不加业务FK，不查询/显式锁alarm/device/trace/tenant以免侵入旧锁序；来源由required同Tx capture及唯一性保证。
- AlarmTraceWriter生成trace UUID后原样写不可变journal，再调用required @MANDATORY capture helper插入outbox。任何capture异常必须抛出，全事务回滚。capture不取publisher/head锁，不要求Creation CoordinationFence、不新加RC要求。无变化恢复/原幂等重放无新trace所以无新提示。不得optional/List空/no-op/REQUIRES_NEW/afterCommit。
- 发布者独立短实际writable READ_COMMITTED事务，先锁新singleton coord，再读取已提交待发布outbox，锁feed-owned行/head；在同Tx给每tenant从1连续分配sequence、first head创建随机UUID stream_id、填outbox publication_sequence并推进head。全部完成才提交。无网络/业务修改/业务行查询，不在外部持久化inflight内存进度。
- coordination只序列化短DB发布，不被业务capture获取。多个发布者和崩溃重试不会把未提交低UUID/早时间漏在cursor后面；publication次序不是业务时间/因果次序。UPDATE只允许publication_sequence从null一次填入、其它安全载荷不改；唯一(tenant,sequence)/trace/delivery与影响行数校验，溢出/故障整批回滚。
- capture始终记录V11后的服务写，即使read/publish都关闭，每个新trace仍增加一条持久写及存储失败依赖；outbox/磁盘/约束故障有意使原业务/轨迹/P7/P5整Tx失败，不声称关闭后业务无侵入。读取和publisher默认关闭；关闭publisher只积压不丢弃，重新启动处理已提交旧积压。本片不做retention/裁剪/TTL，表会持续增长，不承诺容量；不支持直接删表/改head/恢复旧备份后无缝续流。epoch不能自动发现连epoch一并恢复的数据库回退。
- 新字段部署开关 `APP_ALARM_CHANGE_READ_ENABLED=false`、`APP_ALARM_CHANGE_PUBLISH_ENABLED=false`；publisher仅all/worker，读取仅API角色。技术周期 `APP_ALARM_CHANGE_POLL_MS` 默认500、50..60000；批量 `APP_ALARM_CHANGE_BATCH_SIZE` 默认20、1..100，集中校验、可配置。缺省不是业务提醒时限。API-only可读其它worker发布结果；本机flag不代表集群活性。

## 2. HTTP/授权/游标

`GET /api/{management|customer}/alarm-changes`

- 仅alarm:read+现有效单位，无device/历史/通知/其它目录权。PLATFORM显式已grant X-Tenant-Id；CUSTOMER固定session单位且任何tenant头403；source token非用户。每GET重新require鉴权，不从cursor提升权限。
- 严格query闭集cursor/through/size；未知/重复400。present空cursor/through400；through不能单独出现。size用原Pagination，省略实际取全局default，1..100；不认page/from/as_of/tenant_id/SQL/重放开关。
- cursor是规范无padding base64url编码JSON，精确字段 `v/tenant_id/audience/session/stream_id/sequence`。v为原始整数字面1；session为sha256(`alarm-changes:v1:`+当前Identity.tokenHash)的64小写hex，不回tokenHash本身；stream_id为UUID或null；sequence为canonical十进制string，范围0..Long.MAX_VALUE，无正负号/空白/e/前导零/JSON number。编码解码闭集、重复键、UTF8与资源大小都校验，建议上界2048字节（安全技术边界）。
- 规范key序固定v,tenant_id,audience,session,stream_id,sequence，UTF8 JSON再unpadded base64url，reencode须完全相同。这是可检查的坐标encoding，不是加密/签名/凭证；服务端始终比tenant/audience/session fingerprint与fresh身份。跨用户/session/受众/单位400 CURSOR_SCOPE_MISMATCH。允许有权者在自己stream中选择合法旧位置；不能改成别范围权。
- 无tenant head：stream_id=null/sequence0。唯有null epoch且seq0的旧cursor可以采用首次发布的新UUID epoch；已有非null epoch（即使seq0）必须匹配当前head，否则409 CURSOR_RESET_REQUIRED。未来cursor/through大于捕获head返回409 CURSOR_AHEAD；through<cursor为400 CURSOR_RANGE_INVALID。不自动吞错误/重bootstrap。
- 缺cursor：mode=BASELINE，捕获head H，不重放历史，items=[]、next=through=H、has_more=false。
- 有cursor C、缺through：mode=CHANGES，在本次RR捕获head为固定目标T，只查询C<seq<=T；返回through_cursor=T。BASELINE的through只是H元数据，第一次真正检查必须省through。仅has_more时下一页携带相同T；末页后清activeThrough，下次明确检查再次省through取新T，不能一直留旧T。不能后台无界循环追移动head。cursor==T为空末页；对合法连续范围有缺行必须503 CHANGE_STREAM_INCOMPLETE而非假空成功。
- null/0配显式through=null/0后，即使first publisher已使head>0，服务端也只允许把两边epoch归一为当前UUID，位置C/T仍严格0；next/through/scope均同一归一epoch、items空/has_more=false，不把T扩大为H。无through的新检查才以当前H为新T。frontend独立校验只允许这个零位置null→UUID归一，不允许非null epoch被替换。
- 底层outbox的`publication_sequence`仍按单位从1连续分配并由完整性检查核对，但它不是HTTP/WS游标。对外的H/C/T、item sequence与next cursor均是当前actor可见真实发布记录的密集rank，页内从C+1严格递增、无洞；next_cursor精确最后返回rank（空页为C），has_more精确是否还有<=T的可见记录。成功页next不得越过T，不从浏览器max自行造cursor。结束只意味着该固定可见已发布范围已检查，不是所有当前业务提交已发布。
- read关闭：fresh授权后503 ALARM_CHANGES_DISABLED；worker-only仍原API role503。存储/流缺失安全503、不泄原SQL；401沿原session失效，合法403保会话但清本流。拒绝保原DENIED审计，不为零写关掉它。

## 3. 成功wire与读取预算

精确11个顶层字段：

```json
{
  "mode": "BASELINE",
  "as_of": "2026-10-25T08:00:00.123456Z",
  "timezone": "Asia/Shanghai",
  "scope": {"tenant_id":"owned-unit", "audience":"management", "stream_id":null},
  "size":20,
  "items":[],
  "next_cursor":"canonical-encoded-coordinate",
  "through_cursor":"same-coordinate-for-baseline",
  "has_more":false,
  "pending_count":"0",
  "semantics": {
    "kind":"INVALIDATION_ONLY",
    "order":"PER_ACTOR_VISIBLE_PUBLICATION_RANK",
    "publisher_liveness":"UNCONFIRMED",
    "baseline":"HEAD_BEFORE_SEPARATE_WORKBENCH_READ",
    "historical_coverage":"APPLICATION_TRACES_FROM_V11"
  }
}
```
- CHANGES item精确 `delivery_id/sequence/type`，type固定ALARM_CHANGED；sequence是本次授权actor可见真实发布记录的密集rank，不是底层per-tenant source sequence。pending_count是该actor可见的未发布真实记录数；所有序号均十进制string避免JS舍入，size仍原始安全整数字面。不回实体/trace/actor或业务时间；不能把提示数、seq差或积压数叫报警数。

- 成功GET实际active逻辑只读/JDBC REPEATABLE_READ；BASELINE3/CHANGES4固定业务SQL预算仍适用。底层所有发布记录的完整性校验（count/max/head）包括历史published行，不因模拟或actor不可见而跳过；对外head、page及pending_count再按当前actor可见范围与非模拟实体过滤。可见变更按原publication顺序形成密集rank，所以对外游标连续且不泄露不可见行数量；reader不写任何feed或业务状态。认证SQL/事务协议不计此预算。
- publisher_liveness恒UNCONFIRMED，只知本次已提交未发布pending_count，不能从pending0或本机开关说集群在线/无报警。
- `as_of`只是本次求值，不是commit watermark；底层feed head仅是完整性事实，对外游标head是当前授权范围可见真实发布记录的密集排名。pending count也仅是本次RR可见未发布的真实记录量。原Workbench是另一HTTP快照，不宣称二者原子一致。

## 4. 双端显式面板与新鲜度闭环

- 原报警页默认不调用任何新API。只有alarm:read有效scope可点“打开报警变更检查”，二级lazy加载panel/decoder/状态机；关闭销毁cursor/target/page/请求/桥。无localStorage/cookie/URL/token/草稿持久化，无新图标/声音库；主入口<500kB，390整页无横溢。
- 面板只显式初始化、检查/检查下一页、刷新当前工作台并重建基线、关闭。初始化/重建：先BASELINE head H→触发本query/scope/专有nonce对应的实际Workbench重读→原validateAlarmWorkbench通过且可展示该次data才ACK→从H由用户显式检查。不是click/旧data/Date/延时就成功。
- bridge仅AlarmsPage局部；generic useResource/旧P6 wire/decoder/复核模型不改。可加入单调不回零changeRefreshNonce到本页resource key，登记当前query/generation/请求身份；普通Refresh/afterReview/apply/clear/page/size/打开detail或review/新初始化/关闭/token/权限或scope改变/撤权使旧桥失效并reject或abort悬空等待，别次普通成功或旧响应不能完成该桥。
- scan cursor只在整页严格校验成功且操作代数相符后推进；固定T直到本轮末页。失败/坏200不推进，不能采用半页较大seq；状态仅说“已检查该已发布范围”，不显示“已同步/无新报警/实时在线”。
- 见提示后needsRefresh必须由父级当前WB视图持有，面板关闭不能抹掉已知待刷新。显式新Workbench读取若开始于最后已知提示之后且成功可展示，才清对应旧视图dirty；期间又发现新提示不得被晚ACK清掉。失败保留dirty，换单位/session/撤权销毁；不缓存业务row/PII。
- 单位提示不按当前filters收窄，说明可能在当前历史条件之外。只提醒重读，不patch/重排/累计六卡，不关闭/重填复核说明/actor，不自动提交。已有详情/复核打开时，面板初始化/重建刷新禁用并要求先关闭；提示检查本身不触碰selected/detail。
- 合法403清本流进度且保会话；503禁用/存储/网络/坏200保已有待刷新状态，显示检查未完成，不把空页补成成功。scope/session/权限代数/close/并发初始化下，late head/page/WB不得重开旧stream或清新dirty。无自动重bootstrap吞失效历史。
- 新head/page协议独立strict raw JSON/重复键/非有限/闭集、decimal string范围、cursor规范/范围与seq连续/page/target/semantics一致；不声称顺手修复了旧P6 JSON.parse原数值词法能力。

## 5. 验收

- V10→V11既有trace不补outbox/发布历史，已存事实不改；新trace/capture/P7晚失败/原audit晚失败/P5最终fence CAS全事务回滚，缺capture helper启动失败；原幂等重放与无变化恢复0新提示。
- 发布者真实双worker/崩溃/SQL故障、head与outbox原子性/重试唯一/Long溢出；实际PG早开晚提交低UUID在cursor后仍发布，API-only可读另一worker结果，role/开关/暂停积压诚实，不靠内存计数或测试假publisher。
- 成功GET新全schema含新3表零写，拒绝仅DENIED审计；旧Java32表指纹不是新全schema证明。实际RR/BASELINE3与CHANGES4固定查询/旧epoch跨tenant/session/audience/未来/坏cursor/严格page与UTF8/序号超JS安全整数/缺行失败覆盖。
- UI真实AuthScreen两端最小alarm读、默认0新GET、H→专用WB ACK→显式fixedT多页、pending文案/错误/dirty关闭与晚ACK保留、复核草稿不受提示干扰、全部迟到/坏200/raw seq不丢精度/390/无凭据存储；原259断言/2worker/隔离/timeout不改。
- Root唯一最终包完整旧175/259+增量、新H2/原生PG17多JVM与故障/实际Chrome/390/全部旧28阶段、原byte截图/hash/清理。仅自有Windows回环合成，不冒充WS、厂家/渠道/硬件、混版本滚动/已有生产库恢复、Linux/remoteCI、跨机/HA/容量/灾备或全面安全验收。
