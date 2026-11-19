# P8b3 显式选择的有界信号自动重连（本地有限验证完成）

最终C8CE **597项/34阶段/6原图/只自有清理**actual0，[最终合并证明](<../backend/p8b3-final-verification-proof.json>)与[验证记录](<verification.md>)锁同hash，不复用D896候选。默认仍关，数字集中配置；无自动业务补拉/实体刷新、无生产/真实厂商/WSS/HA/无丢失认证。

## 工作账本

- Goal：goal-2ed11442-649d-458c-802e-ef97131307cc。2026-10-26 09:18用户继续开发；P8b2已完成，不resume已complete旧goal。
- Core：①重连只恢复同owner的失效提示通道，不读/推进人工HTTP C/T、实体或统计；②每次用户选择只有不可续期window及单调retry budget，恢复成功/心跳/短暂连接不重置预算，明确撤权停止。
- Verified01：gap后Root读P8b2完整账本和路线，原最终CDBA/30440123bytes仍精确相同，原223+315=538与32阶段为既有出口；未当成新切片通过。原byte独占归档[基线](<../artifacts/p8b2-CDBAA4D3F67E1402.jar>)。
- Verified02：39/40并行严格只读，尚无任何新source/build/test/服务；两owner独立指出普通same-origin GET不保证Origin，不能把原B2 POST缺Origin拒绝照搬进GET。新policy无票/无Cookie认证，GET缺Origin可放行，出现则仍strict allowlist；真正ticket POST/Upgrade继续原strict Origin，不信Host/Forwarded。
- Verified03：Root读B2 Socket73行/Panel46行/strict parser30行及controller28/policy26行。现connect()在首frame前resolve并吞失败，不能作为重连成功证明；新supervisor须消费可选typed ready/ended，不parse中文state。原B2手动所有315断言/2workers/isolation/timeouts不改。
- Frozen04：39/40独立只读均认可最小独立GET与可选typed生命周期；Root冻结：已有当前B2手动首valid才可opt-in、点击T0、同次server connect timeout界定policy GET与auto bootstrap预响应、不在loading原conn失效后arm、成功不续预算。N最后一次可在剩余window结算，window/stop只取消未valid候选并保healthy；429/503、精确native失效与deadline见下文白名单。无任何source/build/动态成功据此产生。
- Verified05：39/40只读均complete后正式授权42/43。BE新2主源/2test已冻结：d778d3707df0471a actual collect0，17新测试（11 Spring+6 policy）/09:48:41+08/1m05；e2d10fab700f4ddd actual collect0，新17+旧直接相关26共43/09:51:22/1m16，151main/41test编译0，未full/clean/package或服务。预计全BE240/40XML须Root实际全跑，不拿43替代。新4/3SQL及全schema成功0写是Mock实测，不冒充PG native。
- Verified06：Root task44新三夹具仅源码/syntax；限写child分别交新TCP代理与新Chrome脚本，无服务/browser/JVM运行。Root已读代理全512行，接管后加避开预选target port的有限bind选择；Node控制改node:http（undici fetch可能自带Sec-Fetch-Mode而control故意拒该头），不放宽控制口。尝试对子child UUID的send_message报active teammate not found，未通信到，不是产品失败；child结束后Root才接管修改。代理仅物理断已验101真实TCP，无fake frame/close；另Node-only能力口不进入页面。尚无新native/Chrome结果。
- Open07：FE owner首build c038d9bf41394c11已collect0，后be1c5e81d13a4e12 build0/direct fake-clock25通过，但新UI一个it串8动作触原默认5000ms超时、390另失败待独立定位；不能当full绿，也不放宽原315/2workers/isolation/timeouts。Owner只拆新it为独立cases并精确诊断，不把连带原因当已证明。Root已只读review3新/改源（104/101/50行当时快照），待正式冻结及全FE真结果。
- Verified08：FE43正式src/test/dist冻结。最终ac49f28c5b3c42ca actual collect0：build0、新41/41及原相关80/80实际0、full356/356/17files（可见full10:18:53/75.80s），原315/2worker/isolation/timeouts未改，旧B1管理case原样通过但不据此归因前次超时。Owner后校准focused精确开始/耗时和个别case毫秒未从pruned tool中段独立核，不作精确日志事实；只保实际counts/exit与可见full时长。c038 build0、be1 108/110与656 118/120失败全保（656旧B1超时+新T/草稿长例超时，full未进），只拆新例保断言与390已实际过。UI新增由真实policy显示delay/window毫秒，无新interval/默认值。Root独立byte核4源、6关键dist与全43资源到backend/p8b3-frozen-frontend.json；eager419501+common75650=495151<500000。
- Verified09：Root唯一9540e926461b4d8f actual collect0，完整40XML BE240/0F/E/S（Maven5m30/10:32:29+08）+FE356=596；D8962D27656AE21DFC03D59AF8DBA9812E23FD2645191F4FA58538B8E6A7F263/30449583bytes，43dist/322 Java21 class/11 migrations全部原byte同包，eager495151。backend/p8b3-root-package-proof.json actual0，不拿focused43/旧538顶替。
- Verified10：同D896新H2 cb17108046ea4146、PG4f4bb82d000b4a03 首轮均actual collect0，10:36:01/10:36:32+08结束；各17 FIRE/2单位启停/10真实已验101 tunnel物理drop、17 genuine native sockets/actual1006、无N+1/窗口不续/stop保healthy/真实latebody+callback+旧owner/当前C-T与草稿/AUTH wholeWB均过。Root逐张read6原PNG再独占原byte归档，docs/screenshots/p8b3-original-proof.json actual0；两独立profile客户document可见静态区域相同而原SHA相同，不从另profile替代。Fixture曾短暂加post-exit proxy stats字段后立刻原样撤回，无最终source差异，运行进程均在启动解析原源，不拿未执行的附加字段当证据；proxy本来由ExitStack要求退出成功。
- Open11：原32按原flags同D896重跑，a0cf0dcce64f4646 H2十九、cdfc8bd6f22f438c PG十三含旧B2。首次只读progress已见H2前13/PG前7 stage pass，其余仍运行，不计34完成。Root仅编辑docs/新汇总与cleanup verifier，不改运行fixture/SUT包；无cleanup结论。
- Verified12：显式25-doc local-links首查exit1/506中3个历史B2 dist chunk已由新构建替换；未删引用/冒用新bytes。Root从已验证CDBA原归档提取全部43 static，逐条比当时package-proof SHA/size并独占归档artifacts/p8b2-frontend，proof actual0；旧6关键（含仍存在但hash已变HTML）链接改原byte历史。再查507/25无missing且new proof Python/cleanup PS syntax0；这是限定inventory非全仓/remoteURL证明，最终新增链接后还需重查。
- Confirmed13：BE/FE独立只读均确认Root路径及原闭集按HTTP status/headers分界，不能追改成‘完整bootstrap未确认’追认D896。Socket59的timer至完整text/parse才clear，已获200头后body悬挂会retryable true继续有限POST，虽仍N/window有限却具体超闭集；41缺该向量，不拿绿盖。尚是Source控制流确认，不冒称已动态反例。Root task45正式限写Socket+新test+ownhandoff+dist：先新1个200 Response.text held真实fake-clock反例跑旧源实际1F，再optional current-owner后响应阶段标记/无Response超时才retry、已200后bounded body-timeout terminal且latebody不新WS/POST；保原41/315/2worker/timeouts与429/503/manual。预计新42/full357，原596/D896/new2/旧32均仅候选历史，必须新包597与全部新34重证，不能换文义或混hash。H2旧十九a0cf已actual collect0，PG仍运行，Root不在其结束前覆盖Jar。
- Verified14：45仅先加第42例，旧Socket SHA186D...12EBB未改时 df32b861be4944eb actual collect exit1，1F/41skip，真正Response200+native ReadableStream/text原方法已开始，1000ms stage截止再100ms delay后 expected总POST2 got3；不是timeout/编译/环境失败、不是自然网络实测。finally放流防残留。现才授权/开始最窄response-phase与已知stage绝对期限修复，manual/429503/41/315不动；busy JS延迟timer时status采用前也须stage guard，避免晚401先clear再判过期。D896旧PG十三cdfc也actual collect0，候选34全部已收归档；candidate-proof首次仅decoder别名utf8-sig拼错LookupError（未生成文件）改utf-8-sig后actual0，不当产品失败/新覆盖。
- Verified15：45 a89ad35205534a9c actual collect0：targeted1P/41skip、build0、新42/42、原相关80/80、full357/357/17files（11:21:00/62.40s）；同新增42 case还以busyJS不派timer+已过known阶段后401验证status guard不清当前session且无当前Response timeout仍可有限retry。源只Socket新增无payload onResponse、response-phase/body-timeout与known绝对stage guard，旧41/315/2worker/isolation/timeouts不动；supervisor/Panel SHA原样。Root已读Socket108行/新42全部流+late401断言并独立核4源6关键dist/43资源，backend/p8b3-frozen-frontend-final.json actual0；新Socket53C877...3442E/newtest5558FA...15F6，eager仍495151。
- Open16：Root确认绝对clean target与CDBA/D896候选都已原byte归档后唯一修后整包a78b41a4d8d64a30开始，log backend/p8b3-root-package-body-fix.log，完整BE240/全597仍待真实exit。Root仅新proof的final文件名/常量597与最终图suffix分离，不改运行fixture/SUT源码；45 source/dist再次正式冻结/complete3。
- Verified17：a78b41a4d8d64a30 actual collect0，修后完整BE240/40XML/0F/E/S（Maven4m21/11:30:03+08）+FE357=597；C8CE98F7CF71B5025E4A3D1DB4C2F3207E88B3F72C791F75392C09E1CCBEF499/30449636bytes，4源/43dist/322 Java21/11迁移全部原byte同包，eager495151；backend/p8b3-root-final-package-proof.json actual0。1472a968fb274569 H2与5b27b1430d1f4bd5 PG新native/Chrome同C8CE已开始，仅新两链运行不与旧32并行；尚无新runtime通过结论。
- Verified18：C8CE新H2 1472a968fb274569/PG5b27b1430d1f4bd5均actual collect0，11:34:40/11:35:14+08结束，各17 FIRE/2单位操作/10物理已验101隧道断开/17 native sockets且既定语义通过；全新源run d600a33ba42d4cf6bde22bf6e0718dc1/0193c3245bf647baa79e5710c0a7d812。Root6原图重新逐张read，archive-helper final suffix独占copy并SHA/size/源report核同，docs/screenshots/p8b3-final-original-proof.json actual0，不用D896图代替。
- Open19：C8CE原32同新包6f0fbe212fb4419f H2十九、378091b9bc3a4b91 PG十三已开始；原命令/浏览器flags未改。Root仅docs/新汇总helpers，不改运行fixture/包。仍须两链全部真实exit、post-runtime 4源/43资源/322类/11迁移byte核、glob标记/terminal/CIM/alias保PG18与34合并，未收口。
- Verified20：C8CE原H2十九6f0fbe212fb4419f于11:51:06、PG十三378091b9bc3a4b91于11:54:56+08均actual collect0，四新receipts锁新包1+1+19+13=34，原flags/browser/断言不变。Root运行后package-proof对4源/43dist/322 Java21/11迁移再次0，C8CE/30449636bytes不变；全部Root相关runtime jobs已收。Root glob全.runtime postmaster.pid/init-password.tmp各0、terminal_list实际空；cleanup 11:55:46.640+08 actual0，精确主Jar/项目Java21工具JVM、Root脚本Node/Python、项目PG17/自动化Chrome/alias0，保既存PG18 service4904/main7520/10-16创建，不广杀或删marker。
- Verified21：final-proof初次exit1仅严格预期H2枚举误用h2，实际result读到h2-dev/prodfalse；改成严格{h2-dev,postgresql}+prod bool一致后actual0，未改SUT/断言/存储出口，也未在失败时生成final文件。backend/p8b3-final-verification-proof.json核597/34/6新原图/cleanup及完整inputs/log hashes，只有限Windows self-owned loopback。当前25-doc checker另查D896 ownhandoff历史3 chunks失效，Root从不可变D896原包核43 static再归档，owner仅修旧6指针、旧数字/hash不变，Task45新6仍指当前dist；再查543/25无missing，最终收口新增链接后554/25 actual0且无missing；仅显式inventory本地目标/line bounds，不是全仓/remoteURL认证。
- Remaining：本子切片本地有限出口闭合；P8后续自动业务补拉/实体流/故障运维消息页与真实厂商/渠道/生产、多机WSS/LB/容量/灾备/全面安全评估仍各自待验，不用本轮样本替代。
- Scope：Lead独占本文/scripts/CI/Rootdocs与最终package；backend/frontend尚未授写权。相关约束见[P8b2合同](<alarm-change-channel-contract.md>)。

## 1. 不扩展的边界

- 仅用户先完成H→指定工作台真ACK、原手动连接首valid frame后，才可选择有界自动重连；页面打开/初始化/普通HTTP检查/登录/显式权限恢复都不暗启。
- 原B2 POST bootstrap精确5字段、WS frame4字段、ticket/header/Origin/当前授权、资源cap与native async不改。原P8b1 BASELINE3/CHANGES4与固定T不改。
- 不自动HTTP补拉、重读工作台、排序/改统计、提交复核、弹窗/音频或领取；不保证无漏报/发布器在线/后台定时精度/容量/生产/WSS/HA。
- 同一panel一个supervisor。原手动connect/disconnect/reconnect、initialize/rebuild、关闭/unmount、scope/token/generation/user/permissions/audience或authorityEpoch改变均销毁旧plan；不持久化opt-in、deadline、ticket、raw token或retry队列。
- 原普通hint与自动恢复不改Sheet/复核草稿。明确授权变化沿原authorityEpoch屏障隐藏数据/销毁Sheet，仅真显式WB恢复，不复活自动计划。

## 2. 独立策略读取（已冻结）

`GET /api/{management|customer}/alarm-change-reconnect-policy`，无query/body，Bearer/current alarm:read/audience/tenant/grant/unit/password与all/api/ready门。管理端带固定tenant header，客户不带；只接自有session范围。

- 缺Origin允许，仅本GET是普通受保护read；若Origin存在，则只允许恰好一个且通过原B2 canonical allowlist，拒null/重复/错误。没有宽CORS，不从Host推断，也不称Origin是本GET身份。
- 需原read/ws enabled及新reconnect enabled，否则503。新flag默认false，仅控制新plan的policy读取，不是服务器即时取消所有旧client plan的安全开关；服务器始终以原ticket/current auth/cap为准。
- 精确3外键：`{v:1,scope:{tenant_id,audience,session},policy:{max_retries,delay_ms,window_ms}}`；scope与policy均精确3键。scope精确同B2 bootstrap.scope三键，并匹配当前cursor的tenant_id/audience/session（不是cursor六键全部），raw整数/duplicate keys/空体/越界/额外字段/错scope均fail closed。无ticket、URL、raw hash或数据库cursor能力。
- 成功不发票/不占连接许可、不写audit/idem/业务。使用原filter fresh authenticate/require+tenant，management固定4条identity SELECT、customer3条，不重复调用hash-refresh变8/7；无需新DB时间/事务/feed/settings/COUNT查询。4xx拒绝保原DENIED，503/5xx不假称也有DENIED；CUSTOMER单位停用仍原401。GET在当前HTTP/1 profile拒正Content-Length或任意Transfer-Encoding，不读无界body；缺length=-1或0合法；容器层坏framing不冒充已进入应用DENIED。成功全schema零写需实际验证。
- 新启动配置：APP_ALARM_CHANGE_RECONNECT_ENABLED=false；APP_ALARM_CHANGE_RECONNECT_MAX_RETRIES=3[1..10]；APP_ALARM_CHANGE_RECONNECT_DELAY_MS=1000[100..60000]；APP_ALARM_CHANGE_RECONNECT_WINDOW_MS=60000[1000..600000]，window>delay。均仅有实际消费者的技术值，不是业务默认或撤权SLA。前端不硬编码第二套运行值。

## 3. opt-in与预算状态（已冻结）

- B2只增加可选typed生命周期：首validframe的ready（含该次server connect timeout数值/不含票），及已分类ended。原无supervisor路径保持全部现有行为与文案。
- supervisor包装同一个B2 socket，不另开并行socket；策略loading与原手动连接绑定。点击时捕获完整owner/authorityEpoch/当前ready connection身份，并以performance.now记T0；policy GET用该已知server connect_timeout_ms设置abort deadline，no-store/omit/redirect error/AbortSignal及完整owner+plan guard先于status/body。
- loading中原conn断/换/权限变化就终止读取，晚policy不arm；策略失败不动HTTP位、草稿或已valid healthy连接。真正current401沿原notice/clear，旧401忽略。401/403/非允许状态/坏200不自动重试policy。
- 有效policy后expiry=T0+window_ms，不从响应到达重新计时。若已过期立即不arm。只读取一次policy，不在重连成功后刷新预算/重新读取policy。
- plan pending恢复尝试总数≤max_retries，当前用户原手动连接不算retry；active/preparing计划重复enable不重置；显式stop或计划自然结束后，若当前valid healthy仍合格，可由新的人为click+新一次policy读取开始新轮，绝不自动续预算；每次auto attempt在dispatch时从同一baseline的当前有效HTTP C取anchor（不是WS W，不固定旧C），普通HTTP翻页/工作台刷新不重置或停止计划；每次恢复在新ticket POST实际dispatch前消耗一次，503/429/network失败也消耗。成功仅首validframe确认，不按101/onopen或connect Promise；成功不返还/重置retry数、不推迟expiry。
- 固定delay，不指数/jitter/离线事件/页面visible自动重启。supervisor只持一条在途恢复链，无双ticket fetch/并行候选socket；阶段计时器有界（policy加载时原healthy socket及其silence timer可并存，window timer与B2 watchdog也可共存，不因此改旧手动timer）；timer触发后每次发请求/采用结果/构造WS前重验owner/plan/expiry，后台冻结醒来不能补发过期计划。
- window是重试计划窗口不是healthy连接寿命。到期不再恢复：若已有valid连接则回原手动连接模式并保观察高位，若未valid等待/在途就abort/close。N是尝试次数上限：第N次既已计入，仍可在剩余window内结算，不能刚扣到N就把最后这次未valid候选取消；其成功则保healthy并结束自动计划，其失败则不再排N+1。停止自动计划则立即取消等待/未valid在途但保healthy；明确“断开变更信号”同时终止计划与transport。
- 可重试闭集：ticket fetch无HTTP的网络异常、auto bootstrap预响应deadline、连接/静默deadline、native opaque1006、going-away1001、已协商精确version的1011 CHANNEL_UNAVAILABLE；有HTTP响应的ticket请求仅429/503；“预响应”严格以尚未取得Fetch Response/status/headers为界，已获200后body/解码悬挂仍受已知deadline有界abort但必须terminal，不把“完整bootstrap对象未确认”重分类成无HTTP。因stop/expiry/旧owner的Abort不重试；policy GET失败一律不自动再GET。onerror只观察，等待实际ended/deadline且每attempt ended仅一次。正常1000、未知code/1011非精确reason、1008（除原明确AUTH控制）/1009、错误echo、binary/坏frame/epoch回退、坏bootstrap/raw/空成功/其他HTTP状态均terminal，不猜权限或logout。
- 仅当前native协商精确version且1008 AUTHORIZATION_CHANGED沿原barrier，并先关plan；其余403/失联不伪造authorityLost。全部晚policy/status/body/frame/close/timer不能覆盖新scope或重启被停止计划。

## 4. 反证与出口（待实现）

- 新unit覆盖严格policy raw/闭集/范围、ready-only opt-in、点击T0/held policy超期/原conn在loading失效、每次attempt单调且成功不续budget、429/503/网络、deadline/background晚timer、stop pending/inflight/valid、late旧401/close/timer/owner、auth barrier、HTTP fixedT/观察高位与草稿不变，原538完整保留。
- 真实native H2/PG：策略GET成功全schema零写/拒绝DENIED、无票不占cap、默认off/worker/范围与Origin有无、数字边界；原B2先前所有反证不弱化。
- 真实Chrome双端最小身份/390：默认0新policy/自动请求、真H-WBACK/手动首valid后显式opt-in、真正server进程退出/重启或真实native关闭所致的fresh票/新native连接，重复恢复也耗尽总budget；明确区分受控晚真实事件与天然网络行为。node-only producer/control，零凭据/票日志与截图，保草稿/原C/T、不假造frame/closecode或HTTP结果。
- Root唯一最终整包（原223+315+新增）、新2与原32共34同包必要阶段、原PNG逐张read/原byte/hash/资源及编译类核同、全部job收集、仅自有进程/PG alias/terminal清理，保留既存PG18。测试只本地功能，不冒充完整安全或生产认证。
