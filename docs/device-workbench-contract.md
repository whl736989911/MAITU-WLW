# P8a2 双端设备筛选工作台合同（已冻结）
> 本文保留历史合同或验收记录；原始报告、截图、JAR及未公开合同不随公开源码发布，文中历史结论不代表当前版本验收。

当前管理入口不要求先选全局单位，默认读取全部已授权设备，再按独立树栏的单位/位置子树并集收窄；清空范围不读取业务列表。行详情使用记录所属 `tenant_id`，不会因旧操作单位而读取另一单位。见[当前范围说明](../README.md#当前范围)；以下历史冻结结果不作为新版本验证。

## 工作账本

- Goal：在163后端+239前端与D3完整包/26有限运行阶段基线之上，补现有设备状态/关键字筛选及所有匹配页的同口径统计，不冒充实时、故障引擎或完整设备运维。
- Core：①同一个DB as_of和实际只读RR内，过滤、统计与分页采用完全相同的有效离线策略与范围；②最小device:read无其它目录依赖，未知/非模拟/待联调不冒充正常/厂商验证。
- Verified01：原管理设计设备列表状态/关键字筛选、客户设计状态/关键字筛选尚未由当前基础列表覆盖。原产品/分组/信号/故障/地图等超出现有事实与权限，不纳本切片。已读DeviceQueryService/OfflinePolicySql/useResource/新P8a1 lossless wrapper，先与双方只读审阅，未授权实施。
- Verified02：backend/frontend已完整只读审阅并明确认可9top/5filters/9metrics/**6defs按本文，不用备选5defs**/7query、bool空400/原独立轴。DQ新方法直接复用private select/row及现settings.defaultPageSize单SQL；前端抽lazy最小raw-row helper、保P8a1私有extra白名单丢弃语义与原24/239，不制造合成JSON envelope，不改旧P6。高级仅详情，不混Editor/历史/开户，模式回基础重置全部条件；390入口保图标可见。
- Verified03：独立静态反例审045d32已完成，未建立具体合同矛盾，列明所有动态残余，不是全面安全审计。Root纯隔离H2 2.3.232/JVM64m探针在实际JDBC RR=4先COUNT2后并发更新/删除/插入，页仍a/b旧组且不见c phantom，fresh连接提交成功；首命令仅未引用-D参数被PowerShell拆错class而未执行目标，引用参数后exit0。探针只证明H2仪表/基本行为，不是新API验收，不新增生产锚点/隔离改动。
- Verified04：task27已完成/后端source冻结，3生产处改动+新12组；编译685b11ef exit0，首54 f20ef1旧42全0/新11过，唯一newtest隐私断言误禁合法offline_policy.source_id，仅改新断言路径后cb51be08新12独立collect0（13:48:18，1m09），不伪称单次54全绿。含真实aggregate后update/delete/insert phantom/4业务SQL、原独立轴/全17Row/三层有效策略/零写/outer guard。Root已读3生产改动，无具体新增源码缺陷；预计full175，尚未装包。
- Verified05：Root新HTTP/原生PG夹具已写并py_compile0，Node子agent47e18仅单新脚本语法0已交回，Root完整静态读并修正自有新XSS检测marker为DW及外单位旧WB双marker；两者均尚未在新包运行。Native仪表只新私有DB function/role，成功全schema/拒绝DENIED分离、真实GET phantom/policy/fresh/第二API/worker503；脚本不增生产hook。CI仅新增新HTTP和nativePG步骤，远程未执行。
- Verified06：task28 complete/source/dist冻结，最终2e362ffa actual collect0：build、44/44（新20+原24，13:56:43/14.90s）、完整259/259（14files，13:57:00开始/78.23s）。原239/2worker/隔离/timeout不动。历史新TS exact错误仅newtest修；首新19/20冷lazy helper过早getLabel，只newhelper await findLabel；table-only marker/LEGACY中性语义纳最终全套，P8a1 default不动。主495232bytes/495.23kB<500，WB11.38、sharedRow2.55、CSS73.45；全四job已收，无服务。
- Verified07：Root独家完整clean package1baec28 actual collect0：175/0/0/0、32fresh XML独立核总，与FE259共434。2026-10-25 14:11:36+08、29,300,347bytes，最终CBE2BBB5B1A3F18FFB6CAEE96300C2EC6C680BC0593D1B1D366F09C286C48B9F；7嵌入asset逐byte匹配冻结FE（index/F38,WB/D0,row/CD3含CSS/设备页/详情）。D3仅历史基线。
- Verified08：新H2+Chrome301b3e与nativePG/prod+Chromead91ff首次整串实际collect0，reports57cd6f(h2)/e5aee4(pg)已Root/BE读。17设备/字面6与5+1/五AND/false/九全量oracle/原17位P5电量/默认5显式1/保存v1迁v2/三层策略皆0；PG全schema成功0、拒绝仅DENIED；实际GET gate跨插入/移出匹配+策略60→240旧组/fresh新组、role/function恢复/第二API/worker503皆0。三真实最小UTC Chrome contexts基本/高级单表、不暗读目录/旧list/报警，真实heldA→B、403合法session恢复、一次原始近整数坏200拒绝、390/Sheet实几何全部0。PG两原viewport PNG已Root分别看并bytecopydocs，131060bytes/1440×1080及31383/390×844，hash详验证记录。
- Verified09/Closed：同CBE旧H216全部0（216d首3实际0；第四错误CLI并未执行场景，Root正确node身份入口起余13的7a27b5全部actual collect0）；PG10的1288bc全部actual collect0，含旧P8a1。新2+旧26共28个有限阶段全覆盖，不伪称首wrapper一次无错。未动旧脚本/源码/断言/timeout或重装包。两owner限定closing均已收到且授权Root仅各README/BE两handoff最终追加。
- Final：2026-10-25T14:39:56.300+08，CBE/29,300,347bytes前后不变；精确自有JAR及实际browser-script进程0、runtime无postmaster.pid、SUBST0、terminal空。首cleanup谓词误把含查询文字的DSH runner载体当browser，实际查命令身份后仅排除载体重核0，无进程终止/删文件/源码变更。Root/owner所有相关job已收、task26/27/28已完成，29由Root闭合；仅限自有Windows回环合成环境，非全面安全、Linux/remoteCI、真实厂家/渠道/硬件、跨机/HA/容量/灾备验收。
- Next：task26冻结完成→task27 backend与task28 frontend get/claim后实施/定向测试/完整FE/source冻结→Root完整装包/H2/PG/Chrome/390/旧链/原字节截图/清理。当前goal-352333c2-a667-419f-9ff3-ba8fecf5412d，不恢复已完成P8a1 goal。

## 0. 范围与兼容

- 仅新增双端设备工作台GET与设备页的显式高级入口；不改schema、新权限、业务数值/默认日期、写API/领取、WS/SSE/自动轮询、真实厂家/通知/硬件。
- 旧devices分页、summary、单设备detail/effective、管理CRUD/迁移/历史、所有报警及P7模拟通知接口、权限、Clock消费者与写语义保持。原163+239完整保留，不靠修改旧断言/超时/worker数过关。
- 新高级模式仅设备查看与已有P8a1只读详情；编辑/迁移/绑定历史等在原基础列表继续使用。本切片不在高级表混挂DeviceEditor/AlarmDetail或自动取目录。
- 成功读取无设备/报警/参数/事件/原文/任务/通知/轨迹/审计/幂等写；401/403/坏参数仍保原DENIED安全审计，不能为零写关安全审计。

## 1. 权限与query

`GET /api/{management|customer}/device-workbench`

- 只需device:read+当前有效单位范围；不需summary:read/alarm:read/products:read/参数或单位目录权限。PLATFORM显式已授权X-Tenant-Id，CUSTOMER固定会话单位且任何tenant头403。source token不是用户401，非API运行角色503。
- 仅接受page,size,keyword,connectivity,physical_state,integration_state,is_simulated；未知或重复参数400，包括device_id/tenant_id/product_id/sort/as_of不能覆盖范围/顺序/时刻。
- page与size复用现Pagination/PageSizePolicy，默认条数来自原全局可调配置，不新增或硬编码业务默认。稳定按device.id升序，不支持任意SQL排序。
- keyword复用LiteralLike.normalizedKeyword/contains；字面case-insensitive包含，仅查设备保存的name/device_eui/address，与旧基础列表字段一致。Unicode trim/NUL/非法UTF16/长度边界按共享规则；%_!及SQL/HTML片段是文字。
- connectivity仅ONLINE/OFFLINE/UNACTIVATED；physical_state仅FIRE/NORMAL/UNKNOWN；integration_state仅LEGACY_SIMULATION/PENDING_INTEGRATION。未提供或空字符串为未过滤；其它值400，不猜新状态/自动大小写转换。
- is_simulated为三态：未提供→null；提供只接受精确小写true或false，空串/1/0/on/off/TRUE/任意字符串400。不能用JS/Spring宽松truthiness将false变成未筛选或true。
- 所有筛选AND，连接/物理/接入/模拟是独立轴；ONLINE+FIRE、OFFLINE+NORMAL、UNACTIVATED+FIRE均不能自作推断重写，LEGACY不蕴含模拟true，PENDING不蕴含无上报/离线。没有报警未处理/故障/低电/信号衍生筛选或未实现分组授权。

## 2. 响应与口径

响应恰9字段：`{items,total,page,size,as_of,timezone,filters,metrics,metric_definitions}`。

- items沿既有完整17字段DeviceRow，offline_policy为安全有效值，不返回原文/产品schema/来源凭据/actor/reason/日志。固定绑定元信息不取latest目录。
- as_of一次DatabaseTime.now，规范UTC微秒；timezone=`Asia/Shanghai`是口径说明，ISO offset原样。不用应用Clock或浏览器时间重算连接状态。每行offline_policy.evaluated_at须与该响应as_of同一instant。
- filters恰5字段：`{keyword,connectivity,physical_state,integration_state,is_simulated}`。keyword是normalized字符串；其它enum/string或null；is_simulated必须JSON boolean或null，不回字符串。
- metrics恰9字段且均非负整数：`{device_total,online,offline,unactivated,fire,normal,unknown,simulated,non_simulated}`；对所有AND筛选后的全部匹配设备，而非当前页或原全单位总体。
- total=device_total；online+offline+unactivated=total；fire+normal+unknown=total；simulated+non_simulated=total。选择某连接/物理/模拟条件后，相应桶必须等于total、同轴其它桶为0。空页/页外仍是真正全匹配统计；无匹配才全部0。
- ONLINE/OFFLINE由本次as_of与每台实际GLOBAL→TENANT→DEVICE有效分钟阈值比较，边界等于截止为ONLINE；last_message_at=null恒UNACTIVATED。不能把未上报归OFFLINE，也不重写设备时间。
- FIRE/NORMAL/UNKNOWN仅当前设备物理字段，不是未处理报警数或全部活动报警。simulation仅is_simulated标记；non_simulated不得称“已接通厂家/硬件已验证”，PENDING_INTEGRATION也不是离线/故障。
- metric_definitions恰6个固定字面：`scope=FILTERED_ALL_PAGES`，`connectivity=EFFECTIVE_OFFLINE_POLICY_AT_AS_OF`，`physical_state=CURRENT_DEVICE_PHYSICAL_STATE`，`simulation=DEVICE_IS_SIMULATED_MARKER_ONLY`，`binding=DEVICE_SAVED_METADATA_NOT_LATEST`，`consumer=QUERY_CONNECTIVITY_ONLY`。
- 工作台快照与随后打开的P8a1设备详情是独立HTTP/求值，不能把工作台行/as_of冒充当前详情，不能把as_of称提交水位、最后上报或历史补拉游标。

## 3. SQL与事务

- 新public入口验证实际active逻辑只读事务+JDBC物理REPEATABLE_READ；拒绝加入外RC/可写RR，不改旧helper/方法要求，不靠自调用注解假升级。
- RR内共4条业务查询：默认page策略、一次DB时间、SQL单aggregate、稳定LIMIT/OFFSET页。共享同一个固定OfflinePolicySql投影（含同as_of绑定）及tenant+全部筛选where/bindings；可用CTE/派生表过滤computed_connectivity，不依赖同SELECT WHERE别名的方言偶然行为。
- COUNT/SUM聚合在数据库一次完成，页只取有限行，不为每行查参数、不独立COUNT/N+1/Java全库统计。不因新入口取coord/行写锁；固定查询数不等于大规模O(1)或容量已验。
- 复用既有17字段映射，允许提取域内纯mapper或明确package-private复用，避免复制后与详情分叉；不引入反向领域依赖。旧device()/devices()/summary/effective的Clock/锁顺序/返回保持。
- 真实RR并发试验须在已有业务投影/aggregate实际执行后、页前放并发设备/父策略提交，旧组统计+行自洽，下一请求见新组。H2虚拟clock查询不锚首次业务表；不加第三锚点查询/改SERIALIZABLE迎合夹具。

## 4. UI与原始响应校验

- 原基础设备列表默认保留原入口/动作/API和P8a1详情；新增明确“打开设备筛选工作台”，高级模式独立lazy组件/API/strict decoder，基本/高级不并列两张表。进入/退出模式先销毁旧detail/editor/notice/敏感局部state，旧基础useResource禁用/abort，不在高级后台读旧devices或目录。切换时将basic page=1、size未指定、keyword/draft空，返回基础重新GET默认旧列表，不复活原basic条件/旧成功。高级标题按钮使用已有图标/适用移动样式，不能被390既有heading按钮font-size0规则变空白。
- 高级只有现有查看详情动作，按新响应真实row.id打开旧P8a1独立GET；其余写与历史继续基础模式，客户不露管理动作。高级退出/单位/session/权限/对象代数清结果/条件/请求/详情；不持久化筛选或凭据，不复活hidden旧state。
- 筛选草稿与已应用条件分开；查询/清空回第一页，换页沿已应用条件；改变草稿不GET，刷新沿已应用口径，不隐改草稿。高级应用/清空/换页/改size/刷新须先关该表打开的旧详情，不将旧row/as_of当新结果。显示server实际filters/as_of/全页口径；错误/403/404/坏200不留旧指标或补0，仍合法403不退出会话。
- 原始完整JSON拒重复键/非有限；total/page/size/全部9metric及每行revision/version/offline整数均按原始span普通安全整数字面校验，不能只JSON.parse→safeInteger。boolean严格含false；battery保原decimal token，不parse/stringify丢精度。可抽共享安全行验证，但旧P8a1/旧P6行为不变。
- 验证身份唯一、行数与页offset、total与所有轴关系、filters echo/定义常量、每行同as_of策略/当前tenant有效来源、枚举与所筛条件、NULL/未激活关系、已知页桶不大于全量；全部行可见时桶须完全一致。文字按文本，不执行SQL/HTML/脚本片段。
- 主入口<500kB（基线495.23），新逻辑不静态拖入主链；390稳定整页不横溢，内部设备表可横滚，不做伪图表/默认日期/新业务数值。

## 5. 验收门槛

- 保旧163/239，增量覆盖最小权限/两端/真假boolean/组合AND/字面特殊词/跨单位/未知与重复query/NULL/rev0/精确decimal/固定绑定与三层策略/边界与同次as_of/真实RR/4查询/成功零写与拒绝审计分开。
- UI增量覆盖真实AuthScreen→显式ID→基础→高级，默认与切回旧入口不改；无目录/隐式alarm，独立详情，草稿/分页/刷新、迟到scope/旧page、坏metadata与booleanfalse、模式/撤权销毁、390语义与预算。
- Root同最终包H2与原生PG17实际API/Chrome（两端最小device读、真实特殊名/多页、只读全schema/拒绝审计、实际PG并发与第二API配置、worker role），真实390及全部P1–P8a1旧链、最终hash/嵌入原字节/PNG和自有资源清理。代码、语法、组件语义与本机合成全绿不替代远程CI/Linux/生产/厂家/硬件/容量或全面安全审计。
