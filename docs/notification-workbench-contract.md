# P8c1 通知记录安全只读查询工作台（Root冻结v1）
> 本文保留历史合同或验收记录；原始报告、截图、JAR及未公开合同不随公开源码发布，文中历史结论不代表当前版本验收。

## 工作账本

- Goal：goal-4d414f64-dfe6-4127-a5ef-9dd385428039 rev2，用户2026-10-26 12:07继续开发；前P8b3已complete，不resume旧goal。
- Baseline：C8CE98F7CF71B5025E4A3D1DB4C2F3207E88B3F72C791F75392C09E1CCBEF499 / 30449636bytes / BE240+FE357=597 / 34有限阶段及只自有清理已0。C8CE原byte归档证明实际核JAR与43 static，今后不把新target/dist冒作旧证据。
- Reviewed01：Root源读发现P7已存在双端通知列表/详情/attempt/history与管理操作，故本轮不是重复实现/改名。task46/47仅只读，源码/测试/dist尚未授权修改。Root48独占合同、脚本/CI/文档/最终集成。
- Reviewed02：真实权限为notifications:read，客户catalog已可显式委派但默认四权没有它；不改seed/catalog/默认grant或实际账号。fixture只给一次性dummy读权，产品继续既有P2显式委派。不存在要由我们擅定的全客户开放。
- Reviewed03：旧management+configread详情有rendered_message，旧management history有actor/reason；新路径即便full-admin也不请求这些，不能先取旧正文再前端hide。ContactPhoneStorage无reveal API，新读不依赖phone/key-ready/解密/worker或模拟开关。
- Reviewed04：46/47均完成只读，明确接受17 row/9 top/6 filters/9 metrics/5 definitions/3 detail与已有同权；旧22 row草案被更小17取代，不能混用交错消息。DB/V9权威为device_name/address非null（可为空string），仅finished_at/last_error_code可null。
- Frozen05：2026-10-26 Root冻结本v1，源实施另授分离任务49/50，原597不改；此冻结不是实现/测试/运行通过。Source-private原P7/core历史请求不属本轮全局修复承诺，新scope-local读取仅本新增路径。
- Observed06：Root先捕不变源码把41个Java test源误当40测试类而assert失败，实际glob证多一LeaseExpiryTrigger helper；改精确41源码/40原类+17原FEtest后295不变文件采集0，未改产品/测试。C8CE旧handoff六静态指针仅改向核43原byte archive，旧数字/hash未动。
- Observed07：49首focused26为2F，实际新observer漏createStatement DBwall SELECT、宽泛retry子串误击wait_retry；只新fixture精确修+补聚合bad/sum两反例，新增28实际0（ea2af059e8c540c7），相关73=28+45也0（fb7f5da26c9f419c），源码4Java一次写出后未改已冻结，预期全BE268须Root整包实测。不把focused相加成覆盖。
- Observed08：50首build0/新36全0但旧P7 48/49有原case79回归（非timeout）：新basic宿主过度permission/token rekey跳过旧detail的bodyAllowed重读；只撤新宿主额外key，旧测试不改。Root源审提出datetime-local候选，新37 DOM反例386a05e1a1f44319实际1F/36skip：六位.123455输入.value为空串（jsdom原生控件，不称真实Chrome自然故障）。仅新组件两输入转text+明确北京µs格式，原反例保留，后续GREEN/full/真实Chrome未出不得提前称通过。
- Open09：51/52 fresh子工仅新Python/Node脚本及各自handoff，syntax-only0，未运行SUT/服务/browser。Root对plain子工UUID两次send均actual notfound、未送达，不能说已交换schema；Root待子工交回后读实际两份source合并private-stdin字段/µs/metrics/cases/cleanup，并实际验证，绝不拿syntax当验收。所有主源/最终dist未冻前不Root package。

## 不变边界

- 仅现有P7的真实持久化SIMULATION通知记录；不是站内收件箱/已读未读、实时消息、真实短信/外呼或服务商回执。
- 不新建业务表/迁移/worker/依赖/业务阈值，不发真实网络，不触发排队、重试、核对、取消、已读写、报警/设备/轨迹更新。旧通知API/操作页和原597断言、2workers/isolation/timeouts不改；B1/B2/B3 wire/C/T/权限与草稿规则不变。
- 原通知页默认仍原basic；有权用户显式打开懒加载“只读查询工作台”，可返回原页。不新增App/PageName/nav权限、不混同原接入messages:read，不自动轮询/重连业务补拉。
- 只读GET仍每请求原fresh auth/单位范围：PLATFORM显式X-Tenant-Id及grant；CUSTOMER固定单位、伪header拒绝；错audience/无权403、停用客户原401、跨单位/不存在ID404、worker-only运行角色/存储未ready503（不是worker_enabled=false）。成功无写，4xx DENIED与503/5xx分开，不把拒绝audit说成全请求零写。

## 拟冻结后端接口

- `GET /api/{management|customer}/notification-workbench`；同notifications:read，无body，不以通知开关/phone-key/worker gate历史读取，success no-store。
- query白名单page,size,keyword,status,channel,dispatch_id,created_from,created_to；拒未知/重复。page默认1、size省略由原集中PageSizePolicy，Limits原page<=1000000、size<=100、keyword<=100；不加新业务数字。正Content-Length/任意Transfer-Encoding拒绝且不读无界body，普通同源GET无新Origin票规则。
- filters精确6：keyword（字面trim，空为''）、status/channel/dispatch_id/created_from/created_to（空规范null）。status只原8个，channel SMS/VOICE；dispatch exact opaque ID<=64，不凭空限定UUID。时间显式offset、0001..9999、最多微秒、转UTC；from>=to400。[from含,to不含)，无“近7天”等默认。
- 原8状态：PENDING,WAIT_RETRY,SUCCEEDED_SIMULATION,FAILED,UNCERTAIN,CANCELLED,SUPPRESSED,REJECTED。无RUNNING；模拟成功不等于送达，不把UNCERTAIN归失败或收件箱未读。
- Result精确9：items,total,page,size,as_of,timezone,filters,metrics,metric_definitions。
- metrics精确9：notification_total,pending,wait_retry,succeeded_simulation,failed,uncertain,cancelled,suppressed,rejected；8状态sum=notification_total=top total，空集合真0，后端异常不伪造0。口径同WHERE/所有匹配页，不浏览器累计/当前页冒全量。
- metric_definitions精确5常量：scope=FILTERED_ALL_PAGES,time_field=RECORD_CREATED_AT,time_bounds=FROM_INCLUSIVE_TO_EXCLUSIVE,mode=SIMULATION,external_delivery=NOT_APPLICABLE_SIMULATION。
- keyword只对record id/alarm_id/device_id/device_name/address五个安全文字字段做绑定参数字面匹配，不搜联系人/规则/模板/正文/错误码，不开放动态sort/tenant字段；前端不另造与PG/H2不同的Unicode SQL casefold。
- 一个真实RO/RR读取：TransactionRequirements先验当前实际事务/连接isolation，固定4业务SQL=PageSizePolicy1+DBtime1+aggregate1+page1，即使显式size仍取当前默认配置一次。identity原管理4/客户3另计；连接metadata观察不是伪造业务SQL。一次构造tenant+mode SIMULATION+AND predicate供aggregate/rows共用，显式安全列、不SELECT*、无join/N+1/FOR UPDATE；created_at DESC,id ASC。
- 同一aggregate内部附不进wire的invalid_count，在本matching WHERE内检查未知status/channel并验COUNT=sum8，坏数据不能丢row凑0；其他mode明确不属SIMULATION来源而不计。返回row/detail仍逐项校验mode/枚举/既有level1..5/attempt_count0..20/日期/非空列，失败整响应用值无关503 NOTIFICATION_READ_UNCONFIRMED，不回显坏原值/SQL/堆栈；不用500细节充错误说明。
- last_error_code key必有，值仅null或这版源核19系统码：ACTOR_UNAVAILABLE,ACTOR_CHANGED,AUTHORIZATION_REVOKED,AUTHORIZATION_CHANGED,TENANT_CHANGED,UNIT_DISABLED,POLICY_CHANGED,RULE_CHANGED,TEMPLATE_CHANGED,CONTACT_CHANGED,SIMULATION_TARGET_REQUIRED,NOT_FOUND,ALARM_HANDLED,SIMULATED_RESPONSE_UNKNOWN,SIMULATED_RETRYABLE_REJECTION,SIMULATED_ATTEMPTS_EXHAUSTED,SIMULATED_REJECTED,CANCELLED,SIMULATION_RECONCILED。DB VARCHAR/catch ApiError.code仍开放，不假设永远封闭；未知非null整响应安全503，不改成null/成功。仅finished_at/code可null，null不推状态，其他17字段保原值；不加created<=finished/as_of或终态必有finished等无DB保证关系。device_name/address可含人工文本，按转义显示、仅结构性不选敏感字段，不夸称任意人工文字绝无PII。
- as_of为此请求DB wall观察（UTC原微秒，timezone Asia/Shanghai），不是提交水位/值守保证；下一页是新HTTP快照，OFFSET可能移动，不当稳定feed。
- `GET .../notification-workbench/{id}`不接受query/body，同notifications:read与tenant+mode=SIMULATION条件；精确3 {as_of,timezone,record}，固定2业务SQL=time1+row1，无page-setting/attempt/history/config/dispatch/contact/template读取。
- record及items同一精确17安全字段：id,dispatch_id,alarm_id,device_id,device_name,address,channel,mode,level,status,attempt_count,available_at,created_at,finished_at,last_error_code,receiver_label,external_delivery。无rule/revision/retry配置等本查询无消费者字段；只有notification_records本行作为来源，未展开的automation_intents/dispatch/原始接入消息不计入。receiver仅级别标签，mode=SIMULATION/external_delivery=NOT_APPLICABLE_SIMULATION；永不带rendered/template/body、contact/phone（含mask）、actor/reason、lease/worker/scenario/sealed信息。管理configread也不扩投影。

## 拟冻结前端

- 新lib/component/test为主，旧notifications页仅显式lazy模式入口；旧默认操作与测试不变，无App/nav/shared api-core修改。高级页不调capabilities/config/联系人/模板/旧detail/history/attempt/写路由。
- 独立scope-local GET loader，只复用ApiError/纯解析等，不复用旧request的token-only401清理。完整token/user/generation/permissions/audience/tenant/force-password+signal/操作序号，先guard再status/body读前后/parse采用；同token新generation旧401/403/晚body不清会话/画旧卡，当前403清旧数据不logout。
- draft/apply分离；非法时间不请求、不自动交换/删界，显式reset回无日期全部；翻页/刷新沿applied条件，详情必须独立当前读、验证path ID一致，不拿列表快照当详情，不更新卡片/工作台/其它Sheet或草稿。
- parser严格raw JSON重复/extra/缺键、枚举/数字token/常量、safe integer与分页长度/唯一ID/稳定排序、filters回显/总sum/已过滤状态关系、时间界/页内已知下限；坏或未知一律未确认而不是0/成功/空状态。数字不偷偷round，无新增默认timeout或自动请求链。
- 390可读、无水平溢出，真实毫秒/微秒与模拟边界原样展示；不以浏览器当前时钟推导执行状态/倒数，不新增interval。

## 冻结时的待验清单（现已由下节Root有限闭合）

- BE反例：原fresh权限/双端最小权/两单位/客户伪header/停用与撤销；全8state/跨页/AND/字面%_!/Unicode/精确µs边界/越界/未知重复query；真RO-RR+固定4/2+success零写；无密钥/功能关/worker历史语义，管理config权也无敏感投影。
- FE反例：严格raw schema/数字/filters/metrics/排序/详情身份；默认无NWB请求、lazy显式/最小read-only/无mutations，draftapply与重置/分页/刷新/详情、完整旧owner late401/403/body与scope清理，390；保原357不改。
- Root源冻结后唯一新整包全原597+增量，新H2/nativePG+真实管理/客户最小读权/390+只读网络firewall与schema/no secrets，原34同最终新包必要阶段（新两链+原19H2/13PG），再全36唯一阶段闭合。focused/retry/candidate不额外计覆盖；6原PNG逐张read后原byte归档，43或新实际全部static/Java21/11迁移原byte核同，jobs实际收，CIM/glob/alias/terminal只自有0并保PG18。
- 冻结时尚无新代码/单测/运行通过结论；不能把此预验计划当结果。不承诺生产/真实渠道/厂商/WSS/LB/HA/容量/无丢失或全面安全认证。

## Root最终观测10：本地有限闭合

最终证明actual0：同8EEBADB517A68EF313B3123507A351E8133A88F23A862E5F546C65CE9D6DAEC4/30,473,062bytes，BE268+FE394=662（原597未改），新2与原34按原flags全36唯一阶段actual collect0；focused/候选/失败/重试不另计覆盖。运行后byte复核核44全部static/332 Java21（原322类不变）/11原迁移/eager495151与冻源，295范围外源观察不变，非Git历史真实性证明。源码/夹具失败与最小修分别见过程记录、BE/FE/原生脚本/浏览器脚本；原17字段/8状态/19码/null/4与2应用读预算没有为通过改口径。

6原PNG已逐张read_image并原byte归档，管理1440×1080、客户document与Sheet390×844；窄屏可纵向滚动，不伪称六条件九卡同屏。nativePG实际RO/RR/并发旧组与next新组、44表成功GET区间连audit/idem同hash；DENIED/auth/fixture写区间分列，H2不冒native/PG wire4与2。全部当前阶段jobs已收，14:20:43+08只自有清理0且保既存PG18与DSH；保日志/数据目录，非删全目录/全系统PG0。真实厂商/渠道/硬件、Linux/远程CI、WSS/LB/HA/容量/灾备/无丢失与全面安全认证仍不在这次有限证据内。
