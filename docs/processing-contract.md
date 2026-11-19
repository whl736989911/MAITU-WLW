# P5a：持久任务与受控模拟报文业务处理

## 阶段边界

承接P4a及全部既有合同。仅固定JSON_POINTER_V1、人工明确提交的模拟源+模拟设备报文任务能推进模拟设备/模拟报警。不是厂家接通、真实来源自动处理、任意脚本沙箱或通知/下行。P4a捕获/人工预览保持原契约、NOT_APPLIED及无副作用，捕获不自动排任务；业务执行结果在新任务/业务投影记录，不能回写原始事实/旧预览为APPLIED。

新增processing领域持有任务/attempt，不新造第二套原始消息/去重队列。可依赖ingestion公开原始快照/来源锁端口、identity公开当前执行授权端口、device公开模拟应用端口、product固定版本解析、configuration/shared。device通过alarm公开端口修改报警；processing不得直接写identity/ingestion/device/alarm表，下层不反依赖processing。追加V6，不改V1..V5。新权限仅补受保护bootstrap-admin PLATFORM根账号。

## 默认关闭与配置

APP_SIMULATED_PROCESSING_ENABLED=false，且APP_SIMULATION_ENABLED必须true。all/api可提交，all/worker才自主消费；api-only可以仅排队，worker-only不开放管理/捕获API。ingest角色仍无消费者能力。关闭任何模拟/处理开关都不能提交/执行旧任务；不能让重试绕过。提交端部署开关关闭时503 PROCESSING_DISABLED（不是撤销用户会话）；缺少用户权限仍403。

部署参数集中验证启动范围、有实际消费者：APP_TASK_POLL_MS=500（50..60000毫秒）、APP_TASK_LEASE_SECONDS=30（2..3600秒）、APP_TASK_MAX_ATTEMPTS=3（1..20）、APP_TASK_RETRY_DELAY_MS=1000（100..60000毫秒）。单worker串行、每次领取一项；不提供无消费者的concurrency配置。上限是部署资源安全边界，不是给普通客户任意放大的业务阈值。健康新增simulated_processing_enabled（共同开关均开）、task_submission_enabled（另需API角色）、task_worker_enabled（另需worker/all角色）。页面以health事实展示本机消费者状态，不假称整个集群在线。

## API及权限

新增tasks:read、tasks:simulate，仅PLATFORM、单位scope。提交/取消必须tasks:simulate + tasks:read + messages:read + device:write + simulation:write，当前DB权限/授权/启用/首次改密检查，actor_password二次确认、Idempotency-Key及审计；客户无任务管理。GET仅tasks:read（按单位），安全元数据不需要messages:raw，也不得返回payload/raw_event/密码/会话/源token。

- POST /api/management/messages/{raw_id}/process-simulation
  body={expected_device_revision:非负整数,recovery_alarm_id:null|string,reason:必填,actor_password} -> Task（200）。strict envelope，不接受调用方tenant/source/device/version/event_type等替换目标。服务端从raw取得快照目标/版本。
- GET /api/management/messages/{raw_id}/processing-eligibility：同完整提交权限、无需actor密码，只非锁读取当前tenant/raw scope元数据，返回{eligible,code,device_id,device_revision,device_is_simulated,source_is_simulated,source_enabled,binding_current,version_current,product_version_id,current_product_version_id}（未知null）；不强加device:read/sources:read目录权限。flag关闭合法用户返回eligible=false/code=PROCESSING_DISABLED。它是快照不是预约，POST全重验。
- GET /api/management/processing-tasks?page=1&keyword=&status=可选 -> Page<Task>，采用真实默认分页设置。keyword字面查询任务ID/来源消息身份/设备名称快照。
- GET .../processing-tasks/{id} -> {task:Task,attempts:Attempt[]}。
- GET /api/management/messages/{raw_id}/processing -> {task:Task|null}（tasks:read），有且仅有该raw的语义任务。
- POST .../processing-tasks/{id}/cancel {expected_revision,reason,actor_password} -> Task。仅PENDING/RUNNING可取消；SUCCEEDED/REJECTED不能装作撤回已执行。CAS冲突409需刷新重开。取消无设备/报警反操作。

Task={id,kind:"SIMULATED_MESSAGE_APPLY",tenant_id,raw_message_id,source_id,source_message_id,device_id,device_name,product_version_id,actor_id,expected_device_revision,recovery_alarm_id:null|string,reason,status:"PENDING"|"RUNNING"|"SUCCEEDED"|"REJECTED"|"CANCELLED",revision,attempt_count,max_attempts,available_at,lease_owner:null|string,lease_until:null|time,lease_token,created_at,finished_at:null|time,last_error_code:null|string,result:null|Result}。
Result={event_id,alarm_id:null|string,event_type:"FIRE"|"RECOVERY",physical_state:"FIRE"|"NORMAL",battery_percent:number|null,business_status:"APPLIED_SIMULATION",time_basis:"PLATFORM_RECEIVED_SIMULATION"}。battery_percent为解析值；缺失不伪造0/不抹掉设备已知电量。
Attempt={id,attempt_no,lease_token,worker_id,started_at,finished_at:null|time,outcome:"RUNNING"|"SUCCEEDED"|"REJECTED"|"RETRY"|"EXPIRED"|"CANCELLED",error_code:null|string}，开始/结束投影可完成一次，历史不覆写成另一次尝试。不向API输出异常栈、SQL、外部可控原文诊断。

## 提交围栏和持久身份

提交即事务写唯一(raw_message_id,kind)任务和审计后才成功；幂等账本仅存task引用，同key重试重查当前权利/开关/明确模拟标记，返回现任务，不重新排队。业务参数改变同key409；已有任意状态任务以不同key再提交409 TASK_EXISTS，不能重放多造报警。不做终态重排/改版本/复活；后续另有显式合同。

raw必须捕获时BOUND、捕获source/目标均明确模拟、当下source已启用/同单位/模拟、当下主源binding_id仍与捕获相同、device仍模拟且当前product_version_id等于捕获版、版本仍已发布；不以新绑定或新版本解释旧raw。expected_device_revision须与当前设备整行revision相同。raw.received_at不得早于设备last_message_at。真实类型一律409，连旧key重试也不能绕过。

保存actor_id与当前app_users.revision作为授权代数，同时内部快照提交时tenant.revision和source.revision；执行均必须仍相同，防止worker观察前disable→enable导致旧任务复活（名称/凭据等变更也保守拒绝TENANT_CHANGED/SOURCE_CHANGED）。不存actor密码/用户token。worker执行时必须同当前actor代数且当前账号启用、无需首次改密、PLATFORM、单位授权及全部提交权限仍在；密码重置/撤权/停用后旧任务REJECTED，不因后来重开而复活。worker不拿旧会话充当授权，不延长用户会话。

## 租约、失败与锁序

唯一PG/H2持久任务真相，不以内存队列/Redis担保持久性。claim短事务使用FOR UPDATE SKIP LOCKED或两库等价安全策略，DB时钟判available/lease（PG用clock_timestamp()，不得用事务稳定now()/CURRENT_TIMESTAMP；H2用实测实际statement时钟）；owner进程唯一UUID，lease_token每次领取递增，attempt_count+1并留attempt。事务提交后运行固定有资源上界的JSON解析（不持有设备/身份行锁），只读捕获版本和原文。

完成事务：先非锁读取定位，再管理协调→actor→tenant→source→device→job（必要alarm随后）。重查所有围栏和DB lease_owner/token/lease_until；任何不符不写业务。使用公开domain服务，在同一事务原子写设备/事件/报警、任务结果与attempt SUCCEEDED；最后完成CAS仍必须未过期且owner/token相同，更新零行抛错回滚所有业务。没有半个报警，也不让过期工作者回写成成功。claim不在持有job锁时等待身份/tenant/source/device，防反序死锁。

过期RUNNING可由另一worker重领；先结束旧attempt为EXPIRED，再追加新attempt。不超过max_attempts（提交时快照），耗尽REJECTED。确定性未知/畸形、版本/绑定变更、设备revision或观测时间过期、授权/开关/目标状态不满足等REJECTED，不机械重试。技术性临时失败最多max_attempts，固定部署延迟后PENDING；错误仅固定分类码。旧owner捕获异常后不能覆盖新owner/已取消结果。中断/宕机保留RUNNING供DB租约恢复，不把未知执行假装失败重做外部网络调用；本轮无外部调用。

关闭处理执行开关后，管理读取与有权的显式取消仍可用；取消没有执行副作用，不依赖提交/worker开关。取消同锁序（可跳过不需要的source/device锁）最终锁job，以revision CAS置CANCELLED/完成当前attempt并使租约失效；完成与取消只允许一个提交。取消旧worker不能再写。任务读取不持锁/不做领取。没有公开claim/complete或任意sleep测试后门。

## 模拟业务应用

使用新device公开应用端口和device自有业务应用幂等键（task_id唯一），不要调用P1旧simulate入口去混淆消息命名空间。device_events消息ID采用服务端保留前缀P5SIM:，P1模拟入口拒绝该前缀（包括旧key），避免用户消息伪装任务结果。旧P1合同其它行为不变。

- FIRE：只创建一个新的模拟FIRE事件和模拟报警；不按时间邻近合并不同raw。用捕获时设备名称/地址快照。设备物理FIRE、最后上报=raw.received_at、存在合法解析电量才更新，revision+1。报警UNHANDLED，绝不自动人工处置。
- RECOVERY：recovery_alarm_id必须明确指向当前同单位/同设备/模拟且物理FIRE的具体报警，且报警occurred_at不晚于raw.received_at。只恢复这个报警，不调用recoverDevice批量恢复；其它活动FIRE仍使设备保持FIRE，否则NORMAL。人工response_state/conclusion不改。未指明/错误/已恢复/时间过期关联REJECTED，不假装恢复成功。
- FIRE却带recovery_alarm_id或UNKNOWN/畸形：REJECTED，不写任何设备/事件/报警。不把缺失/未知变成正常。job失败不更新在线时间。
- 全部使用平台捕获时间作为模拟事件时间，标time_basis；不把原始reported_at声明当可信排序时钟。

V6将devices.battery_percent提升NUMERIC(64,36)，保留0..100约束、历史整数/null，并改公开DeviceRow为BigDecimal，不允许JDBC Integer截断或double中转。前端只读电量及任务结果沿用无损数字片段策略；展示可无损去末尾0，不修改数据/不做任意四舍五入。没有其它新“已联调”状态，integration_state不因模拟应用假装真实硬件已接通。

## 前端

消息详情有独立“提交模拟处理任务”敏感表单，读取当前设备revision，服务端最终验证；真实/未绑定/类型不明/缺权限禁提交，不以“真实来源”标记冒充真实硬件。用户明确输入原因，可输入具体恢复报警ID（不默认、不批量恢复）；提示该任务会更新模拟设备/模拟报警，但不通知/不下行；旧捕获/预览仍无副作用。

增加“处理任务”管理页，按单位/权限真实分页、状态/来源身份/attempt/lease/错误码/结果详情、显式刷新和敏感取消；worker不在本机运行时不能假装队列已处理，不强行以浏览器定时刷新冒充WS。切单位/登出清表单/秘密并取消迟到响应，actor_password不存任务/请求账本/浏览器持久存储。GET无raw_event/payload，即使有messages:raw也不额外加载原文。P4a边界提示改为仅描述捕获/预览，而非否认独立模拟任务可能推进。

## 验证出口

保留65+68旧测试及P1/P2/P3/P4a整包验收。新增V6升级/root-only权限、严格类型/权限/真实拒绝、两worker唯一领取、过期旧owner/取消后不可写、提交丢响应不重复、技术重试与耗尽、重启恢复、跨tenant/客户/撤权/actor代数、源/绑定/版本/设备revision过期、精确小数电量、不同火警独立、恢复仅具体关联报警且不改人工状态、失败零业务副作用。

Lead在原生PG17/prod用api与两个worker同一数据库做真实进程验证：API仅排队，worker角色管理API503；停止/重启恢复过期任务；持有设备锁让owner1阻塞，等待租约过期由owner2接管，释放后owner1不能写而仅owner2一次业务提交；不是仅两个线程MockMvc。无公开运维后门，fixture用随PG17提供的原生libpq自有连接控制锁（发行包不含psql，不额外引入pip运行依赖）且有限清理。H2/Chrome验证任务从消息提交到实际模拟设备/报警、两火警的单关联恢复、取消/无权限/真实拒绝/密文不泄露；全部synthetic，绝无真实厂商/电话/短信/下行。
