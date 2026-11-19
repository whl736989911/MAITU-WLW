# P7a 通知中心受控模拟闭环合同

阶段说明：本页保留P7a手动入口的实施合同。后续[新模拟FIRE自动通知](<notification-automation-contract.md>)通过独立单位显式opt-in扩展同一通知域；下文“不自动监听/不自动排队”指P7a手动入口本身，不否认已单独批准的P7b1策略。原SafeRecord/Attempt/History及客户安全wire、每alarm唯一dispatch和手动队列行为保持不变；任何模拟均不代表真实送达。

## 0. 边界与消费者

- 前置F63完整包112后端+158前端全绿。用户继续补齐非真实数据功能，本片是通知中心的**显式人工模拟**闭环，不是全平台完成、真实短信/电话或真实厂家接通。所有渠道provider/mode恒SIMULATION；无HTTP/SDK/外部回调、无真实BizId/CallId/DELIVERED/ANSWERED。不自动监听新报警发通知；生产自动意图/真实渠道/对账后续接入。
- 报警、设备、原报文、P5任务与P6业务轨迹零改动。无领取/独占处理/无消费者10分钟配置；模拟成功、物理恢复、人工已处理独立。GET与页面打开不排队/不发送。只对同单位alarm和其device都明确is_simulated=true的既有FIRE报警操作；UNKNOWN/真实拒绝。
- 本轮真正消费：联系人/模板/规则代数与不可变发送快照；规则max_attempts、retry_delay_seconds；多级接收人时escalation_interval_seconds实际计算due。创建数值输入无擅定业务默认。队列待发时若alarm已HANDLED，只把待发record SUPPRESSED，不回滚已模拟完成记录。物理NORMAL本身不等同HANDLED。

## 1. 身份/权限/配置

- 新6权：notification-config:read/write（联系人/单位规则，管理端单位scope）；notification-templates:read/write（PLATFORM全局，无tenant头）；notifications:read（PLATFORM/CUSTOMER可显式委派）；notifications:simulate（仅PLATFORM管理模拟提交/取消/核对）。客户新增仅notifications:read，旧用户不自动获得新权；只对受保护bootstrap根迁移补权，首次新根由Limits初始化。
- 读通知列表/单条/attempt/history只notifications:read，不追加设备/产品/联系人/原文/alarm:history目录权；统一投影不带敏感联系人。模拟提交/报警候选/规则选择需notifications:read+notifications:simulate+alarm:read+notification-config:read；配置写需config read+write；全局模板写需templates read+write。所有写当前权限、当前显式单位grant、enabled与actor密码在事务中重验，Idempotency-Key、reason、CAS。
- APP_SIMULATED_NOTIFICATIONS_ENABLED默认false，关闭禁止新queue（503），api角色可持久排队但不能执行；worker/all才消费，worker-only管理/客户API503。既有待发record仍可授权取消，不因开关关闭妨碍止损。模拟UNCERTAIN核对须开关明确true。运行poll/batch/lease为集中有界环境技术参数，不作为未确认业务默认。
- phone使用环境APP_NOTIFICATION_CONTACT_KEY提供的随机32字节base64 AES-GCM密钥，nonce每次随机；数据库存ciphertext/nonce+mask+HMAC指纹，不存明文，事务幂等指纹不得含可离线枚举的裸phone哈希，audit/错误/日志/URL/响应不携带原号码或actor密码。密钥不下发/不在Health。缺key不阻断旧应用启动，联系人写/新queue503清楚说明；只读遮罩仍可用。持久单例key fingerprint首次设置并锁定，不同key实例/丢失key不能悄悄创建重复联系人或使用错误密文；本片不提供重密钥/明文reveal。
- 复用默认页大小与Limits技术上限，列表默认不传size，无隐含today窗口。新policy/capabilities读取提供真实number min/max、max_receivers/max_level、feature/worker/contact_storage_ready；不提供key/密文/服务商秘密。仅具新模块相应read者可读，PLATFORM全局无tenant头。

## 2. 模型/输入

- 联系人：id,tenant_id,name,contact_type,phone_masked,enabled,revision,created_at。type FIRE_RESPONSIBLE/LEGAL_REPRESENTATIVE/CONTROL_ROOM/DUTY_OFFICER/OTHER。号码为中国11位手机号1[3-9]或带区号和明确-分隔的座机0xx/0xxx-7/8位（规范形态及mask由后端统一）；不推断号码实际有效/可接通。同单位phone_HMAC唯一。create phone必须；PUT phone=null保持原号码，显式非空才替换；UI空白转null，决不从列表回填明文。name/type/enabled都明确填写，创建enabled默认false/未启用。
- 平台模板：id,name,channel(SMS/VOICE),provider/mode=SIMULATION,body,enabled,revision,created_at。body白名单占位符仅{{alarm_id}}/{{device_name}}/{{address}}，未知/不配对括号拒绝，无eval/HTML/URL执行。内容创建后不可变；改内容须复制/另建新template id；PUT只name/enabled+CAS（不暗改已用正文）。记录保存当时模板快照不依赖当前标题。无内置APPROVED阿里云模板，不种隐含规则/联系人。
- 规则：id,name,enabled,revision,event_scope=FIRE,mode=SIMULATION,sms_template_id,voice_template_id,max_attempts,retry_delay_seconds,escalation_interval_seconds,receivers[{contact_id,level}]。SMS/VOICE两模板都必须明确选择、类型匹配且enabled。同单位enabled联系人unique<=50；level1..5且非空连续，允许同level多人，单contact只一次（防同号码跨级重拨）。max_attempts 1..20、retry_delay_seconds 0..86400必须整数且创建无默认；多level要求escalation_interval_seconds整数1..604800，单level必须null，不保存无消费者假参数。无未消费priority/商业额度/默认等级数。所有资源上下界集中Limits并在policy真实返回。
- 每alarm一个SIMULATION dispatch，数据库业务唯一，不同key或不同rule不能重复造发送。首次同Tx按接收人展开SMS/VOICE records：level1 due=DB now，levelN due=now+(N-1)*escalation interval。保存规则、模板、联系人代数/安全显示快照、alarm固有name/address、业务数字、排队actor/tenant代数。各record唯一(alarm_id,contact_id,channel)，不可变快照不被后改名/改规则回写。
- record状态：PENDING/WAIT_RETRY/SUCCEEDED_SIMULATION/FAILED/UNCERTAIN/CANCELLED/SUPPRESSED/REJECTED。明确FakeScenario=SUCCESS/REJECTED/RETRY_THEN_SUCCESS/UNCERTAIN由排队者显式选择，不按名字/电话魔法推断；RETRY_THEN_SUCCESS仅首次模拟明确未受理，可按规则预算/间隔重试，预算1最终FAILED。UNCERTAIN绝不自动重发。

## 3. worker与事务

- **本片专用纯函数/单record短事务，不建外呼lease**。先无锁locate候选，再在同Tx按既有coord→actor→tenant→配置固定id排序→alarm/device公开只读围栏→最后record FOR UPDATE SKIP LOCKED的锁序重验due/state/revision与授权、enabled/代数/模拟标记；不得先持record锁再调用ExecutionAuthorization造成反锁。当前安全coord串行是已知吞吐边界，不冒充生产并发通知吞吐。
- fake只能从持久snapshot+scenario+attemptNo纯计算，不得网络/文件/线程/睡眠/任意脚本或接收原phone。attempt+纯计算结果+record+history+audit同Tx原子提交；DB/audit失败或进程强停回滚，可从相同快照安全重算，不能生成假的外部UNCERTAIN。UNCERTAIN仅是本片明确选择的模拟未知结果，不会自动被due worker选择；同due两worker只一次持久attempt，取消及配置变更按同锁序/CAS。开始计算前资格失效REJECTED/已人工处置SUPPRESSED，保留未调用fake证据。
- 此优化只因没有任何外部副作用；未来真实适配器**禁止直接替换纯函数联网**，必须另做持久DISPATCHING attempt→事务外调用→DB-clock owner/token/lease最终CAS，调用后崩溃/响应丢失/租约过期UNCERTAIN且不盲重发，不宣称真实电话exactly-once。本片不冒充该生产外呼故障闭环。
- 当前配置enabled/revision代数改变（停用再启用亦然）不复活已排队旧发送；snapshot只负责保留历史，不意味着可绕过当前授权/停用。未来物理恢复不变成人工处理；只有HANDLED阻止后续待发升级。单条record按due独立，success不代表人工接单，最高实际级后没有额外隐含升级。
- 非UNCERTAIN终态不可重排，首次排队的语义唯一保持。PENDING/WAIT_RETRY可显式取消（CAS/password/reason/key），不取消已受理不确定结果。UNCERTAIN可**显式模拟核对**到SUCCEEDED_SIMULATION或FAILED（不调用fake/不新增发送attempt），追加不可变SIMULATION_RECONCILED history，保留原UNCERTAIN attempt；这不是服务商回执。

## 4. API（/api前缀，下列management，读通知另有customer同路径）

- GET /management/notification-capabilities：全局、相应新read任一，返回mode=SIMULATION, feature_enabled,worker_enabled,contact_storage_ready,limits数值定义与模板变量/类型枚举。调用不带X-Tenant-Id；不隐含授予配置/目录。
- GET/POST /management/notification-contacts；GET/PUT /management/notification-contacts/{id}。POST {name,contact_type,phone,enabled,reason,actor_password}；PUT同字段+expected_revision、phone可null保持。返回安全Contact，无cipher/mac/fullphone。
- GET/POST /management/notification-templates；GET/PUT /management/notification-templates/{id}，全局。POST {name,channel,body,enabled,reason,actor_password}；PUT {name,enabled,expected_revision,reason,actor_password}，正文不变。rule选择使用scope内GET /management/notification-template-options（config:read，不返回正文，只id/name/channel/revision/enabled/mode）。
- GET/POST /management/notification-rules；GET/PUT /management/notification-rules/{id}。POST {name,enabled,sms_template_id,voice_template_id,max_attempts,retry_delay_seconds,escalation_interval_seconds,receivers,reason,actor_password}，PUT同字段+expected_revision；mode/event_scope是后端固定返回，不接受客户端改值；response含接收人contact_id/level及安全masked标题，绝无明文phone。列表可分页，选项不能默认第一条。
- GET /management/notifications/eligible-alarms?page,size,keyword：queue四权，最小安全候选只模拟且UNHANDLED，id/device_id/device_name/address/physical_state/response_state/revision/is_simulated，无device/product/history目录依赖，关键词字面id/name/address。
- POST /management/alarms/{alarmId}/notifications/simulate：四权+开关，body {rule_id,expected_alarm_revision,expected_rule_revision,scenario,reason,actor_password}，key必需。response {dispatch_id,alarm_id,mode:SIMULATION,created_count,record_ids:[...],deduplicated:boolean}（有界<=100），202首次；同key安全重放不重排，当前权限先重验；已语义排队后任何新key均409 NOTIFICATION_ALREADY_QUEUED（即使参数相同/已全取消也不能另造一轮）；同key重放deduplicated=true，不允许换key重复发。UI成功只重读记录，不能从旧POST画最新状态。
- GET /{management|customer}/notifications?page,size,keyword,status,channel,dispatch_id：literal keyword仅record/alarm/device快照，不用contact姓名/手机号做客户侧信道；状态未知400，按created_at DESC,id ASC。GET /notifications/{id}返回SafeRecord；GET /notifications/{id}/attempts与/history各自标准Paginated，全部限定页/当前单位授权，无全库Java/N+1。
- POST /management/notifications/{id}/cancel {expected_revision,reason,actor_password}、POST /management/notifications/{id}/reconcile-simulation {expected_revision,outcome:SUCCEEDED_SIMULATION|FAILED,reason,actor_password}，notif read+simulate，key/CAS，reconcile额外feature=true。当前scope/auth重验后才读幂等。

### 4.1 Wire补充（两端必须一致）

- 所有list/options/eligible/attempt/history均标准{items,total,page,size}，绝非裸数组；page/size省略取现有策略。template-options可channel=SMS/VOICE过滤。config联系人/规则列表keyword字面name/id（联系人不提供phone搜索），enabled可选；全局模板keyword字面name/id、channel可选。所有数值JSON必须真正整数，拒字符串/浮点/boolean及越界，不截断/悄然clamp。
- capabilities固定：{mode:"SIMULATION",feature_enabled:boolean,worker_enabled:boolean,contact_storage_ready:boolean,limits:{max_attempts:{min:1,max:20},retry_delay_seconds:{min:0,max:86400},escalation_interval_seconds:{min:1,max:604800},max_receivers:50,max_level:5,name_max_length:200,phone_max_length:32,template_body_max_length:2000,reason_max_length:1000},contact_types:[上述5种],scenarios:["SUCCESS","REJECTED","RETRY_THEN_SUCCESS","UNCERTAIN"],template_variables:["alarm_id","device_name","address"]}。没有业务default字段，实际值须管理员输入。
- 号码规范：去首尾空白，手机接受11位1[3-9]或前缀+86并统一为+86+11位；座机要求0xx/0xxx区号、一个明确-分隔、7/8位号码，规范为区号-本地号（不猜无分隔号码的区号长度），不接受内部空格/多个分隔或任意国际号码。本地校验不代表号码有效。mobile mask如138****1234，landline如区号-****末4位；HMAC用规范号码+tenant+用途域，AES与HMAC从主key派生独立子key，密钥fingerprint不当实际密钥使用。
- Contact安全DTO字段仅id/name/contact_type/phone_masked/enabled/revision/created_at；Rule DTO另有event_scope/mode与两模板id/name、三个数字配置、receivers[{contact_id,level,contact_name,phone_masked}]；Template DTO字段id/name/channel/mode/body/enabled/revision/created_at（无provider密钥）。expected_revision/actor_password/reason只写input，不在这些DTO回显。template-options仅id/name/channel/mode/enabled/revision。
- EligibleAlarm DTO字段id/device_id/device_name/address/physical_state/response_state/revision/is_simulated，is_simulated恒true，response_state恒UNHANDLED；无手机/当前设备目录/原文。
- SafeRecord公共字段按§5；管理详情有config:read时可加rendered_message，列表和客户永不有。SafeAttempt.outcome=SUCCEEDED_SIMULATION/FAILED/RETRY_SCHEDULED/UNCERTAIN（purefake均同Tx完成）；无持久DISPATCHING/lease/owner/token。未尝试的取消/资格拒绝/抑制不伪造发送attempt，history有证据。history.action=QUEUED/SIMULATED_SUCCESS/SIMULATED_FAILURE/RETRY_SCHEDULED/SIMULATED_UNCERTAIN/CANCELLED/SUPPRESSED/REJECTED/SIMULATION_RECONCILED；from_status初始null，其他枚举即record状态。管理reason可见，客户reason/actor_id/actor_name根本不在JSON。

## 5. 安全读投影/前端

- SafeRecord两端公共：id,dispatch_id,alarm_id,device_id,device_name,address,rule_name,channel,mode=SIMULATION,receiver_label（第N级接收人，不是姓名/电话）,level,status,revision,attempt_count,max_attempts,retry_delay_seconds,escalation_interval_seconds,available_at,created_at,finished_at,last_error_code,external_delivery=NOT_APPLICABLE_SIMULATION。不含contact_id/phone/name/cipher/mac/actor/user/provider/owner/token/raw payload。管理详细如需rendered_message必须另有config:read；客户完全无rendered_message及reason/actor key，防模板正文/备注旁路PII。客户根本不返回敏感key，不用null冒充裁剪。
- SafeAttempt：id,attempt_no,outcome,started_at,call_started_at,finished_at,error_code。无worker/lease/token/provider/raw。History：id,action,from_status,to_status,recorded_at,actor_kind,actor_label；customer无actor_id/actor_name/reason；管理可有reason/actor快照但不带原phone/密码。
- 新4 lazy page：notifications「通知中心（仅模拟）」双端；notification-contacts「通知联系人」、notification-rules「单位通知规则」管理单位scope；notification-templates「平台模拟模板」PLATFORM全局不依tenant。notifications仅read入页；提交还需四权，缺任一不取候选/不开权限后门；后3页customer显式禁止即便伪page。旧dashboard/alarms/详情/查看/CAS完全保留。
- banner「未接入真实短信/电话；模拟成功不代表送达」。label「模拟报警」「通知规则」「模拟结果场景」，无默认对象/结果/业务数字；显式确认「确认创建模拟通知」+reason+当前密码。状态「模拟执行成功（未发送）」「模拟明确失败（未发送）」「结果不确定（UNCERTAIN）」。不确定只允许有权显式「模拟核对」，不能自动重试/假回执。
- 数字约束来自真实capabilities，整数/范围前后端同验；读失败/未知enum/metadata不一致显示未确认，不补0、不从客户端计时/最大次数造attempt。records/attempt/history都实际分页，默认页大小后端决定。phone编辑不回填，close/对象/单位/登出/权限generation清phone/actor/草稿/一次性响应、取消旧读和迟到写；403保会话清密码，409刷新重开，丢响应同业务key不自动新建；敏感缓存/浏览器storage空。
- feature API/decoder在lazy模块，主入口<500kB，保持默认Vitest maxWorkers2、原158断言，不新增外部SDK/依赖。真实390页面与旧page懒加载ready分别验证，不拿组件测试冒充像素。

## 6. 出口与非目标

- 新H2单元/集成、真实PG+多JVM、Chrome双端/390：首次无数据/正常创建编辑启停、phone加密/遮罩/无泄漏/key缺失/不同key保护、模板变量/内容不可变/纯文本、跨单位/受众/最小权限、密码/CAS/幂等、非法数字/无默认、模拟四结果、有限重试/真实due升级/HANDLED仅抑制待发、停启代数不复活、并发唯一发送、停机/审计故障原子回滚及重启不重复持久attempt、明确模拟UNCERTAIN不重发、显式模拟核对留旧attempt、取消、分页与body/客户白名单、切scope晚响应、原alarm/device/event/trace/P5-task/P5-attempt六表摘要不变（通知审计合法新增，不要求整个audit表静止）。
- 保留112+158全部旧测试，完整嵌入新dist JAR，所有旧H2/PG/Chrome回归；源可变工作树无HEAD不能假称git diff证据。最终hash/截图/文档/自有进程清理。所有真实vendor/notify/network/hardware计数0。
- 不把本轮人工模拟叫生产自动通知/真实送达；无商业额度/收费、真实阿里云凭据与外部调用、自动厂家报警监听、屏蔽引擎、任意脚本沙箱、完整自定义角色或设备范围授权。本轮新服务仅处理明确模拟通知，未来实际渠道必须另行验证。
