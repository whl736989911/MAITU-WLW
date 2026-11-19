# 实施账本：自托管平台分阶段切片
> 本文保留历史合同或验收记录；原始报告、截图、JAR及未公开合同不随公开源码发布，文中历史结论不代表当前版本验收。

## 已确定的约束

本工程为自托管软件，不创建妙搭/飞书云应用，不修改DSH GUI。**当前应用仅Ubuntu/Linux，以Ubuntu24.04 LTS amd64为构建目标。** Java21/Spring Boot3.5/JDBC/Flyway，React/TypeScript/Vite/Ant Design6/Lucide/Motion/Zustand；PostgreSQL17为正式数据库目标，现网仍保留单节点H2。低配单机优先，不强制Redis、EMQX、MinIO、Docker；后续分机必须另行实测。历史旧包/旧系统成绩不自动代表当前候选通过，全平台仍按阶段和真实流程验收。

## 当前代码合同与部署事实边界

本文件里的历史发布结果不代表当前源码或部署状态。正式环境的运行记录和验收材料独立保存。

### 本轮核心推送与收据重解析（2026-11-12）

- 四类消息按明确开关分派，轮询/推送去重；既存V66历史监测不会因等价平铺推送重复落库。单指标上报保留其他最近已知指标，原始时间、精确数值、单位及报文保真。
- 故障来源恢复证据与恢复时间独立：已修复但无停止时间时显示时间未提供，未知状态不冒充未修复；迟到未修复状态不降级已知恢复，不以故障修复解除火警。
- 报警/故障已发生项的迟到更新仍归原单位；设备移交后的旧/无时标新发生项保持隔离。PARTIAL/REJECTED收据可经当前密码、revision与幂等键显式重新排队，原文不变；界面只显示已排队，显式刷新后才确认结果。
- 68个既有迁移条目原字节保留，仅新增V67；一致性副本及正式离线升级均保全133张旧业务表的旧列值，原配置、密钥、账号及原安全链路不变。未向生产写入测试数据，未调用云订阅写、真实控制或收费通知。设备开户/控制、基础维修工单、跨单位整位置移交和PG正式切库等仍须继续实现/验收。

### 上一轮原生推送与只读核对（2026-11-12）

- “海康云接入→事件推送”：运行状态和收据为核心，认证说明、配置及云订阅核对按需展开；支持320/390px手机和1440px桌面。切走页签销毁未提交凭据/密码；读范围、操作单位及旧事件页签不变。
- 接收凭据为浏览器安全随机生成的64位十六进制共享地址凭据，AES-GCM密文存储、GET不回传；不是海康签名。原文持久提交后才HTTP200空响应；具体厂商ACK/重试、真实投递及来源真实性仍需海康联调。
- 拉取开关与推送独立。重复信封只增重复计数，推送/拉取同一发生项只生成一条统一事件；保留原始信封、精确小数及`HIKVISION_PUSH/HIKVISION_POLL`来源，不凭复位清除物理火警。移交边界继续隔离旧/无时刻数据；未支持消息和未知资源保留待关注，不伪造处理完成。
- 管理配置使用当前密码、CAS、幂等；账号变化不得静默保留旧接收绑定。云端订阅仅显式只读核对、网络请求不持有事务锁；本轮未实现新增/覆盖/删除订阅，也未调用这些接口。
- 最终21项相关用例通过，非全仓回归。PG首次脚本误比较动态`evaluated_at`、第二次重复使用临时标记均保留失败记录；修正验收脚本后PG真实升级/HTTP去重/状态保护通过，不调整产品语义或伪造旧结果。
- 67个既有迁移条目逐字节保留，仅新增V66；生产原库升级，不以测试副本替换。nginx对回调路径关闭敏感访问/错误日志，公网404检测标记未进入新增日志；环境、密钥、账号及旧业务数据保留。云端开户/远控、其它流推送、实机火警、收费送达、跨单位整位置移交、PG正式切库及HA仍未完成。

### 上一轮首屏与真实事件（2026-11-11）

- 正式加载工作区及图表样式；设备、监测、故障、概览、大屏以真实列表/指标/图表为主，说明、筛选、诊断及低频维护收进组件。设备表45.5px单行；监测值和单位不挤成竖排，时间作为次级列。
- 顶栏明确“操作单位”，与左侧读取范围分离；实际界面验证创建用户、设备移位、短信/语音分别入账，测试仅写隔离数据库，没有测试真实支付、外呼或设备控制。
- 海康原生接口实读发现主键为`enventID`，已兼容该字段及`eventID`输入；100208按官方两种描述的共同语义显示“复位”。事件原文、发生时间、重复版本及归属边界分别保留，不将事件名称当作控制成功或物理恢复。
- 末次13项相关用例通过；增强原文处置断言后单例复验通过，不作为额外唯一用例计数。此前首轮24项中的3处夹具错误及相关5项复验分开留档。PostgreSQL17迁移/归属冒烟此前通过；最终字段兼容收紧由H2用例及真实海康副本覆盖，未再跑最终包PG或全仓套件。
- 原66个SQL/Java迁移条目字节不变，仅新增V65。正式切换使用最新停服一致性备份和原库，未用含测试绑定的副本替换生产；原配置密钥、门禁和账号密码保留。
- 巡更点/巡更记录排除；消防档案仍在交付规划。订阅回调、云端开户/真实远控、实机火警、付费送达、跨单位整位置移交及PostgreSQL正式迁移仍未由本轮完成。

### 上一轮管理流程与边界（2026-11-11）

- 位置页采用真实树与设备联动，直接新增下级、编辑、删除空位置；“移动整个位置”后台预览完整子树/全部设备，在管理锁下核验快照、版本、密码和理由并幂等提交。设备挂载、历史和权限不随树层级重写；旧位置编辑接口改变父关系也要求完整设备范围。
- 用户设备范围使用真实分页设备选择器，支持搜索、位置筛选、跨页保留和明确移除；搜索不提交外层授权表单，未加载已有ID不静默丢弃。原ALL_TENANT/DEVICE_SET与角色独立，服务端逐对象核权。
- 设备行内可直接移位，复用原有同单位事务和位置履历，保留列表筛选与页码；本次不是硬件移动或云端控制。
- 当前整位置移动限同单位。跨单位整体位置树移交、尚未对接的厂商操作和完整旧功能迁移继续作为总范围中的未完成项，不用本轮三个流程冒充全平台完成。


## 分工与写入边界

- 后端：backend/**，负责Maven工程、测试、SQL迁移、后端自身README。
- 前端：frontend/**，负责npm锁文件、UI、测试和前端README。
- Lead：根文档、scripts/**、docs/**、环境工具目录.tools/**及集成验证；不得覆盖同伴未完成代码。

## 后续切片历史账本

早期P2-P8切片账本以下内容保留为历史设计/验收记录。特别是P5“受控模拟应用”、P7模拟通知、旧模板CRUD、旧模拟API及模拟夹具不代表当前入口或当前业务合同，均已退役。当前真实通用消息应用见[消息合同](<docs/generic-message-apply-contract.md>)，真实通知规则/provider见[通知合同](<docs/aliyun-notification.md>)；报警变更游标/信号现按actor可见真实发布的密集排名语义，见[HTTP合同](<docs/alarm-changes-contract.md>)与[信号合同](<docs/alarm-change-channel-contract.md>)。其余旧切片数字与出口只作对应历史版本记录，不证明当前候选或生产状态。

## P9a4/N03：到期站内提醒（本地有限验收完成）

[冻结v2合同](<docs/sim-expiry-reminder-contract.md>)。V17五表54→59，仅三项新平台权限；配置初始关闭且无有效业务默认值，显式平台收件范围，每用户已读独立、处理结束整轮。DB时间转明确时区的日历日期，首启/漏扫只补一条，规则变化与停启不自动重开；来源DATE变化或明确重开产生新代，保旧处理事实。A→B→A通过内部来源版本和不可变台账历史判断；不修改来源资料、设备、库存或外部事实。

管理端独立lazy入口、零默认提醒GET、权限分离、原key未知写冻结/owner屏障/二次密码及回执非当前状态；现有all/worker角色自动消费，api不跑扫描。715BE=原697+新18，811FE=原800+新11，共1526唯一测试及标准完整包实际通过；F8360F51…D6710F/30,801,500bytes/53精确资产/eager496985≤500000，原46依赖/16SQL与除两权限类外的旧应用class原字节保留。

原210旧用例的首次V17/41F见证、五个精确历史target16/当前59派生后的同210全绿，以及双库先行API/worker/Chrome/native与PG全59表零写/故障回滚均已保原，113原件及8先行图不混作最终同源成绩。用户批准V3后，同596源`764416A0…C840`/F836包/`4404A66F…C2F14`授权的统一48条（27H2/21PG，原46后新2）全部通过，最终8新原PNG逐张检查；独立census0所属进程/0PG标记/377短临时目录不存在，四旧根/七历史包和宿主保原。交付入口：最终凭据/完整包。原V2 first-ready580>390失败永久保留；320px真实探针确认CSS就绪不等于严格宽度合格，65原函数/旧协作值保原，业务/CSS/F836包未改。运行源与后验文档单独标识，不以新文档冒运行输入。当前3/18，下一片N04，不称总目标或真实外部接入完成。

## P9a2/N01：手工SIM—设备平台关联（Ubuntu本地有限验证完成）

冻结合同/当前验证账本。独立关联状态与历史，不扩旧Card15/History17或借旧资料`revision`；一机一卡、先解绑再换卡，空关联行保留以拒ABA。读需台账读和设备读，写另需独立绑定权、当前密码/CAS；no-op不造历史/时间，成功审计/幂等仍记，重放原回执不再造提交。默认零新请求、显式lazy入口、未知写只原意图/原key、严格重新读取才恢复新意图资格；不推物理插拔、可用库存或运营商事实。

实际全525BE/60 suites+714FE/22 files=1239唯一测试，非focused重跑相加。完整6211包30,651,308bytes、48资产、eager496172≤500000、原375类raw相同/只2共享类改、新14类、46依赖JAR及V1..14原byte同、恰15 SQL均已实际核验。PG17原生锁/FK/UQ/竞争/回滚/RO-RR/clock/安全投影与H2持久oracle分列。同6211/520冻结源的原42+新2统一44向量全部actual0（25H2/19PG），最终两新mode的8原PNG逐张read、post-run census0所属进程/PG标记/290短临时路径全消失，完整包静态再核及最终门actual0。原一处403探针单位错误与两observer视图/双向分页错误先真实失败保原件，仅新夹具窄修；pilot八图不充本次最终八图，未改产品/原断言/预算。

独立只读复核指出核验器还需将Unit时源与最终源闭合：仅post-run执行器增加既存70原始Unit文件认证、263BE/114FE源与520最终源逐path/hash一致及unit日志一致门，实际转0；冻结运行核验器17C688与实际执行066689独立hash保留，不声称原冻结核验器被执行或当前源曾漂移。post44静态复核首次误选不含backend_tests的薄exact证明而KeyError，保失败事实，仅选择已认证完整P9a1证明后实际0，无重建或业务重跑。五旧JAR、旧Linux389/487源及9704/5424包、PG18原11个PID/parent/创建身份与DSH3080均当前只读复核保留。

N01有限完成，18包总目标仍活动。N02已本地有限完成：已有设备的内置人工资料、未知ICCID保空，不另造绑定；库存直登无审批，领用普通文字、不自动转单位，解绑不回库、回收可用，误报废填写依据显式更正且保历史；仅新增V16四表与四显式权限，每个新人工意图revision+1/历史/审计/幂等。验收为697BE+800FE=1497唯一测试、完整包`9BBBE24F…EAD95`、同快照46条（26H2/20PG）与16张原图逐张检查；唯一标定是新N02观察器总看门狗360s→540s（实测397.4s，历史44与逐请求/断言/矩阵不变）。N03显式时区/提前重复收件范围可配置且初始未启用、已读不等于处理。已实问确认，不重复提问或擅填默认值；后续实现不加审批或真实渠道。

## P9a1已验基线：管理可插拔SIM手工基础台账（当时本地有限验证完成）

[冻结v1合同](<docs/sim-card-ledger-contract.md>)/验收与真实反例。只做当前授权单位手工建档/编辑、Card15列表/详情、History17不可变维护记录；可插拔与可替换、管理方、付费责任、人工报告运营状态独立，UNKNOWN不推断。平台显式sim-cards:read/sim-cards:write和单位grant，写加当前密码、revision/CAS、四表同事务原key幂等/审计；V14只两新表46→48，仅bootstrap-admin条件补权，不补普通账号。原审计闭集不扩，新动作仍UNCLASSIFIED。无运营商API、计费续费、绑定/回收、库存真相、提醒、画像或设备/报警/通知事实改动。

Ubuntu非root实际全55后端suite444（原363+新81）/21前端文件619（原494+新125）=1063唯一测试，完整542492E6499FEF3688868E502FA6899878EE7982B3C3B1F118D9A690528C08AF/30,608,700bytes，47静态资源、eager496172≤原500000。同一487冻结runtime源/同完整包的新SIM H2/PG真实sandbox Chrome与原40命令共42条已全部actual0（H2 24/PG 18），8原图逐张read；完整有限证据及全42后package原byte复核均0。退出后0所属进程/PG标记、413个所属短临时标记全不存在，PG18/11原PID创建身份与既有DSH保留，旧9704与三个原历史JAR不回写。原Linux389保护源/三overlay/9704当前只读hash仍同。新阶段/重复package与focused runs不重复加覆盖，非生产/运营商/远端CI/HA认证。

先保留raw -0、真实H2高NULL排序、标签观察器与Chrome启动失败原件；原自然390px溢出及受控第26次580/390→2rAF后390/390不回算通过。仅新浏览器夹具公开V2固定ready时点、仍64严格ready点/每store32变换，任一ready/row/final越界必失败，无CSS遮盖/放阈值/时限/重试；不是React/CSS根因修复或全时段无闪动保证。最终核验器UTF codec拼写问题只修post-run coordinator并独立hash，运行中487冻结源未覆盖，详验收。

## Ubuntu-only平台迁移（已验基线）

迁移验收记录当前删除范围和实际Linux结果。15个PowerShell入口及旧主机固定p8*证明/运行辅助已从活动scripts退役，原byte保写前558/29快照/清单；CI仅Ubuntu24.04，Bash/JAVA_HOME/Linux PATH/ELF/libpq、非root私有PG与浏览器sandbox统一，无盘符/其它系统fallback。应用Java/TS、V1..V13、依赖/锁文件和原业务断言保持。

Root已在已安装Ubuntu24.04 WSL2、非root whl实际完成全BE363/49XML+FE494/20files=857测试、完整Linux包`97042B845DABE8628546BF4806D9B3DE51A5D019BD9F4DBC2EC89DEB111E50B1`/30,557,831bytes/46资产/eager495830验证0；不是借原6B2E成绩。私有PG17启停/真实Chrome156 sandbox预检0；首次缺库失败经用户明确允许后仅补浏览器依赖与字体，无PG服务或安全策略改动。最终linux-04同新包全40原业务参数回归已实际0（H2 23/原生PG 17），同最终三overlay/冻结源守卫；Node identity一行tmpdir与真实Linux慢peer一条TCP_MAXSEG选项修正均不改Java/TS/断言/原时限，失败原件保留。8张Linux原图逐张read、108原byte证据导出与最终核验0；最终清理0自有PID/257短目录全消失/0postmaster.pid，既有PG18与DSH保留。远端CI/生产/独立内核/真实渠道/HA仍未由此通过。

## P8e1已验历史：单位内组织型设备分组（原宿主本地有限验证完成）

冻结合同及交付验证原件不随公开源码发布。P8e1历史实现：现device:read双端分页读组/成员，PLATFORM device:write加当前密码维护名称/描述及逐台加入/移出；扁平多对多不授权、不改设备事实，独立组revision/CAS/四namespace原key幂等。无删除/树/批量/导入导出/新阈值；V13只两新表、44→46。四action经旧UNCLASSIFIED投影，不冒完整成员历史。未知结果不自动重试或刷新列表，当前403不logout，409资格只显式刷新恢复。

完整包与最终857/40/8原图实际0：6B2E/30,557,864bytes，Root BE363/49XML+owner FE494=857（原737+新120），46冻结静态/eager495830；原343类341 raw相同，嵌套record仅十项typed调试行27→28而非第二项业务改动，342运行代码未变不冒342 raw。原12迁移/依赖/旧断言保留，唯一V12 helper先真实2F再pin12，旧audit清单唯一44→46派生。新H2/nativePG真实Chrome与原38原flags同包40阶段、8原PNG逐张read/原byte归档、post-run/16:33:21+08只自有清理实际0，既存PG18与DSH保留。三项新UI真实RED、TS/fixture失败、首构建扫描输入拒绝、PG01启动协议失败/原参数02全通过及核验器三项有限门补齐均透明，详验证，不重复加覆盖。旧845D交接不回写，活动历史链接用AD55归档导航视图；未把旧资源塞回新dist。不是生产/真实渠道/跨机或全面安全认证。

## P8d1已验历史：当前单位操作审计（当时本地有限验证完成）

[冻结v1](<docs/audit-records-contract.md>)只增当前单位PLATFORM `audit:read`管理GET与独立lazy普通分页页，仍须当前单位grant；不新建审计表、不回填缺失历史、不展示null-tenant全局日志。61动作/13结果闭集与唯一UNCLASSIFIED、UUID/未记录/不公开三态安全投影；没有原始request_id/路径/body/目录、导出/删除/已读、成功读审计写或新业务数字。真实RO/RR、DB时间、同谓词count/page、reader4/身份4应用SELECT与native wire分开。

BEactual owner全303=原268+新35；FEactual owner全434=原394+新40/19files，eager495526/500000，45静态冻结。原662断言保留；唯一旧V10专测latest耦合先actual RED1F后仅两token固定target10（原assert原样），新V11→V12/root-only/幂等另测，见精确派生例外。377先行源观察不回写，只有四既有产品源/该setup/新源与两CI线授权；客户默认/可委派集合、原producer/V1..11与旧scripts不动。Root既有完整package helper（FEbuild→clean resources→嵌45→全303）已actual0，首外层PS5合并stderr把正常npm notice当错的驱动失败保留并只改外层OS重定向、不改helper；同AD55完整737包、新双库真Chrome+原36原flags共全38/八原图闭合均actual0。Root八原PNG逐张read再原byte归档，PG actualRO/RR/并发旧次下次组/三个全44表成功GET区间同hash，H2只持久事实oracle，应用4+4不冒native wire；auth/negative/producer写另列。只新核验器经独立只读复查加exact12迁移inventory与八图唯一/selected-browser绑定（未发现实际包/图片有错），运行后更严格byte复核及17:44:24+08只自有清理均0、保PG18/DSH/诊断目录；不拿单测/语法代运行，也不称生产/完整取证/防篡改/全面安全认证。

## P8c1安全只读通知高级查询（本地有限验证完成）

[冻结v1](<docs/notification-workbench-contract.md>)只增现通知页显式lazy高级查询、创建时间AND边界、同快照全匹配页8态/9指标与独立17字段详情，不重复P7操作，不加站内已读或真实发送。现notifications:read同权，客户由P2显式委派，默认四权不变；无正文/联系人/actor/重试配置或其它实体目录查询。BE4新主源/2test未改旧P7/原240，Root全268/42XML全0；FE新增37/owner全394actual0、原357/配置不变，6位日期DOM反例与basic原行为回归均精确修新代码并保RED。Root唯一有效整包byte证明锁8EEB/662/44静态/原322类与11迁移不变/eager495151；Root首命令只跑Maven而未用既有完整package脚本的FE目录准备，纯BE候选被byte verifier拒绝，保原候选，只嵌冻44资产且所有495原entries不变，见嵌入证明。最终闭合actual0：新H2/nativePG/真实Chrome390与原34按原flags全36唯一阶段同8EEB已collect0，6原PNG逐张read后byte归档；后复核和只自有清理均0，保PG18、DSH与诊断目录。Source/fixture失败史未覆盖，不称生产/真实渠道/无丢失或全面安全认证。

## P8b3用户选择的有限信号重连（已验历史，本地有限验证完成）

[冻结重连合同](<docs/alarm-change-reconnect-contract.md>)保持B1/B2 wire、HTTP C/T、max(C,W)、最小权限、草稿及原538测试。新增纯当前范围策略GET（独立默认关闭的4个env参数），成功无票/许可/写；3top/3scope/3policy，4/3身份SELECT由Spring Mock观察，nativePG另核全schema零写，二者权威不混用。只有当前手动首valid之后再明确click才能arm；点击T0+有限N不因成功/心跳/HTTP/render续期、每次fresh POST前计数，末候选在余窗可结算。stop/expiry保healthy、撤销未确认候选；owner/AE/手动操作清计划；不默认自动连接，不自动业务补拉或实体patch。开关只gate新policy/arm，不是旧snapshot即时kill switch。

42新增17/相关43与43新增41/旧相关80/full356均实际0；旧315/2worker/isolation/timeout不动，新UI长链及旧B1原5000ms timeout等失败事实保前端交接。Root唯一新整包actual0：40XML BE240+FE356=596，D896/30449583bytes，43dist/322 Java21/11迁移包内原byte同，eager495151。新H2/PG真实TCP/Chrome首次均0，各10真drop/17native、无N+1/预算续期、stop保healthy/当前C-T/旧owner与草稿/AUTH屏障已证；6原PNG逐张read并原byte归档。D896候选原32也已全0，但Root追加发现200头后body挂起被错判retryable的缺失向量；新42例旧源actual1F（expected POST2 got3），task45不改原闭集窄修，修后42/full357和Root C8CE完整597/新H2-PG真TCP与Chrome均已0，原32也已同C8CE原flags全0，最终597/34/6原图与11:55:46+08只自有清理全部闭合并保留既存PG18，不拿D896候选成绩代替，不能当生产/WSS/HA/无丢失或全面安全认证，详验证记录。

## 历史P8b2显式WebSocket信号增量（本地有限验证完成）

见[显式信号合同](<docs/alarm-change-channel-contract.md>)。P8b1表、同步capture、RC publisher与RO/RR reader的原3/4业务SQL预算不变；新POST只发行同node/会话/单位/audience/显式Origin绑定的单用header票，pending/claimed/active/closing共用有限许可。统一4字段head采样无实体/积压/计数，head只读Tx关闭后才fresh授权与native async发送；8项技术数值集中配置/启动验证，不新增业务默认。

双端仅显式连/重连，不写C/T或自动HTTP/工作台；同baseline high dedup保草稿。只有当前owner正确协议下精确1008 AUTHORIZATION_CHANGED触authorityEpoch屏障，隐藏旧行/卡/摘要与卸载Sheet；显式恢复需actual-start+本次Promise finally completed及原validator/nonce/query/generation/owner匹配，不复活旧流或草稿。共享request/core/原285与原196不削弱。

Root旧候选90CED完整220+315=535与PG新native/四Chrome/旧30均已0，但H2一次重负载healthy close原因未定，不能归因。Root另对busy/start/phase一致性提出3个确定可交错模型，旧源3F，修为极短Connection一致状态锁/dispatch owner与同锁expiry closing fence后Runtime12全0；原policy/协议/timeout不动。修后Root新CDBA完整223+315=538实际0，43dist/318编译类/V11字节核同，最终新hash双native/四Chrome/390两阶段已actual0，真5000byte text/binary→1009、真实EOF_OR_RESET与healthy/HTTP继续/返许可、PG actual RO/RR+SQLfault及零写也过；6原图逐张read并byte核同归档，原30同新hash全部actual0，最终32（新2+原H218/PG12）在00:42:16+08闭合；00:42:55只读核验项目进程/PG标记/alias/terminal0并保留10-16既存PG18服务，最终hash/长度不变。Chrome测试context只对此loopback Origin显式LNA consent，不全局禁安全。仅本地有限验证完成，不是生产/真实渠道/无漏报或全面安全认证，详见验证记录及最终证明。

## P8b1报警变更持久补拉增量

见[报警变更合同](<docs/alarm-changes-contract.md>)。V11新APPLICATION轨迹与安全outbox同事务；capture始终运行，read/publish开关默认false不能免除新增存储写及故障耦合。独立all/worker只锁feed自己的coord/head/outbox，实际writable RC后发布每单位连续位置；UUID/time/revision都不是补拉水位，无业务FK/旧轨迹回填/TTL。双端仅alarm:read，fresh范围授权与会话摘要绑定的严格规范游标，11top/3item/5semantics，实际RO/JDBC RR与BASELINE3、CHANGES4业务查询。提示不是报警实体、数量、因果顺序或在线承诺。

默认/打开面板都0新GET，显式H→本次nonce/query/generation对应旧工作台validator兼容可见成功ACK才ready；首check不带through，更多页固定T，到尾清除T。父dirtyEpoch跨close保留，只能由确实覆盖最新提示的更晚成功工作台读清除，复核草稿/原直接CAS/幂等不变。坏200含空200/204不借旧数据ready、不推进、不新造WB桥。独立lazy与旧shared hook/P6 decoder不改；主index419501+eager common75650=495151字节，未把common隐藏出预算。

冻结包E889原字节/43 dist文件与V11/6新Java21类一致，Root完整196后端和owner最终285前端（原175+259全留）已过；最终新H2/原生PG/真实Chrome/390及旧H2 17、原PG 11全部保原参数通过，共30有限阶段。漏PG --browser的API-only复跑不冒充原出口，后补原10PG+Chrome均实际0；18:34:05+08自有运行资源清理与包身份复核完成，保留10-16已存在PG服务。只以验证记录真实出口为准，不用组件测试冒充像素/生产或WS。原生低UUID/Long输入是明确私有publisher模型，不伪称实际业务轨迹；纯client kill during COMMIT允许原子两态，只声称本次实际COMMITTED分支。

## P8a2设备筛选工作台增量

见[设备筛选合同](<docs/device-workbench-contract.md>)。新双端GET device-workbench仅device:read，7严格query、9top/5filter/9metric/6defs；DB实际只读RR内默认条数/同一次UTC微秒as_of/共享固定SQL计划aggregate+page共四业务查询，原private十七字段mapper复用。不借summary/产品/参数目录，不增schema/写/默认业务数字/WS；成功零写与拒绝DENIED分开。连接、物理、接入与模拟独立，non_simulated只标记，不作厂家验证。

原basic列表默认与旧API/P8a1详情/管理写继续，显式高级才lazy读新接口且停止旧list请求；高级只独立当前详情，切换回基础清条件重读。两lazy模块共享原始span Row验证与decimal字面，旧P8a1四字段/旧P6行为保留。完整175后端+259前端/CBE新H2/PG/双端Chrome/严格390已过，同包旧16/10全部0并于14:39:56+08核验自有运行资源清理；最终身份、历史修正及有限出口以验证记录为准。

## P8a1设备只读详情增量

见设备详情合同。保持旧接口与业务写不变，新增双端`GET /devices/{id}`（仅device:read，四字段as_of/timezone/consumer/device）和`GET /devices/{id}/alarms`（另alarm:read，device_id+原工作台九字段）。后端分别实际只读RR/2与5条查询、父设备归属及固定tenant/device SQL过滤；不查询产品schema/参数目录，不写业务、成功审计或幂等。拒绝保留原DENIED安全审计。旧全单位工作台仍只需alarm:read。

设备事实/所存绑定/有效策略与报警固有名称地址快照独立；两个HTTP不拼成同一快照，DB as_of不是历史或提交水位。新lazy宽详情只从真实列表ID进入，显式关联读取、草稿/已应用条件分开，精确数字原文及微秒北京时间校验，关闭/范围/身份代数清理，不挂复核/历史/通知写界面。没有增加业务数字、WS、地图假值、领取或真实外呼。

Root完整163后端、前端完整239及同D3包新H2/原生PG/真实Chrome/390已通过；当前最终旧链/清理状态与摘要只以验证记录为准，不把源码、组件语义或部分阶段当全平台验收。下节仍保留第一切片历史wire，新增字段及UNKNOWN等以后续合同为准。

## 第一切片API合同（历史起步记录，已退役，不是当前接口指南）
下列API/字段是首切片当时的wire快照，已被后续实现取代；不得将其用于当前调用、部署、验收或变更权限。
基础 /api，JSON snake_case，ID用字符串。错误 `{code,message,request_id}`。分页 `{items,total,page,size}`。浏览器只内存保存Bearer，不persist令牌。运行端口后端8080，前端开发5173（均可配置）。开发代理/api至后端；生产静态资源可由后端同机提供。

- GET /api/health：公开，`{status,storage_mode,simulation_enabled,roles}`。
- POST /api/auth/login：`{username,password,audience:"management"|"customer"}` -> `{access_token,user:{id,username,name,user_type,tenant_id,must_change_password},permissions:[]}`。
- GET /api/auth/me：返回user及permissions。
- POST /api/auth/change-password：`{current_password,new_password}`，服务端吊销旧会话，前端重新登录。
- POST /api/auth/logout：撤销会话。
- GET /api/management/tenants -> items；平台用户的租户业务操作必须用已授权 X-Tenant-Id 请求头。首个bootstrap平台管理员显式授予demo租户访问。
- GET /api/{management|customer}/summary：`{device_total,online,offline,unactivated,today_alarms,unhandled_alarms}`。
- GET /api/{management|customer}/devices?page=1&size=20&keyword= -> 分页；设备行 `{id,name,device_eui,model,address,connectivity,physical_state,last_message_at,battery_percent}`。未知值null，不填0。
- GET /api/{management|customer}/alarms?page=1&size=20 -> 分页；报警行 `{id,device_id,device_name,address,event_type,occurred_at,physical_state,response_state,conclusion,revision}`。
- POST /api/{management|customer}/alarms/{id}/review：`{expected_revision,conclusion:"TRUE_FIRE"|"FALSE_ALARM"|"TEST"|"DEVICE_ALARM",remark}` + Idempotency-Key，成功更新人工处置为HANDLED，物理状态不变。过期revision=409。
- GET /api/management/settings -> `{items:[{key,value,unit,min,max,description,revision}]}`；PUT /api/management/settings/{key} `{value,expected_revision}`。首批数值离线阈值和列表条数等落在集中定义和可校验设置，不承诺全部配置域完成。
- 旧示例 `POST /api/management/simulation/events`、`simulation:write` 与模拟火警/恢复操作均已退役；不得按此历史例子调用或创建模拟业务。

本行及其后首切片字段说明均为历史描述，不作为当前操作指南。

## 接口补充约定

本节其余字段/权限枚举与模拟响应均为历史首切片描述，不能用于当前API实现或验收。当前认证消息应用、真实通知规则和报警变更合同以上方“当前代码合同与部署事实边界”中的链接为准。

实际首切片枚举：设备/报警物理状态FIRE或NORMAL（正常/已恢复），人工状态UNHANDLED或HANDLED；与完整规格的ACTIVE/RECOVERED及多阶段工作流做明确映射，未假装多阶段已完成。首次改密及退出成功返回204；改密后必须重新登录。health在bootstrap提交并ACCEPTING_TRAFFIC前返回503。

数值offline_threshold_minutes初始2880仅为可调开发建议，不是已批准生产阈值，24小时心跳设备不能默认30分钟离线。首批只做有明确生效说明的读时推导，不假装后台检测、离线通知已实现。

## 验证边界

环境核查：当前目录不是git仓库，无现成应用源码；node/npm/pnpm/git可用，Java/Maven/PostgreSQL未在PATH发现。下载工具仅放本工程.tools，不改系统全局环境。PostgreSQL生产迁移和H2开发/测试可以分离；H2模式必须显示仅开发并禁止误当生产。必须运行测试而不是只写测试。

## 第一切片历史起步记录（已执行，操作示例退役）

历史起步计划曾建立前后端工程并验证登录、租户隔离、模拟火警去重、复核与恢复分离；这不是当前版本测试结果，当前实际结果与待验边界以验证记录及父级补充证据为准。
