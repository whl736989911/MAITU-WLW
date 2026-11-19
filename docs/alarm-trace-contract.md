# P6a1 报警当前详情与不可变业务轨迹

## 交付边界

在P1..P5a/P3b1之上补安全详情与业务留痕，不先发明领取租约、完整ACK/PROCESSING/CLOSED状态机、批处理、通知、故障引擎或下行。不添加待批准的业务数值默认；分页仍既有default_page_size，硬上界仍集中Limits。物理FIRE/NORMAL、人工UNHANDLED/HANDLED与通知/下行四者独立，手工复核不恢复，恢复不完成处置。原复核POST/响应/幂等合同兼容。

新报警创建、每次成功复核、指定报警模拟恢复、旧P1按device模拟恢复，均与实际报警状态修改在同一个事务追加一条轨迹；失败/冲突/同key重放不追加。P5完成末次租约CAS/审计失败回滚轨迹，不能单独事务先留成功轨迹。仅既有正常业务函数能写轨迹，没有公开“补写/编辑/删除历史”接口。读详情不会领取、复核、恢复、更新revision或产生通知。

## 迁移与完整性

V8追加，不改V1..V7。alarm自有alarm_trace_entries。历史已有报警只在迁移时插一条MIGRATION_BASELINE快照，revision_after=迁移时真实当前revision；before=null，after=当前状态，remark=当时已有最后备注，recorded_at=实际迁移时间，occurred_at=null，actor_kind=LEGACY_UNKNOWN。不得把旧occurred_at/备注猜成历史创建/复核/恢复，不假设过去操作者。id可baseline-<alarm_id>，字段长容纳。首次新报警FIRE_CREATED条目after revision0，后续每条对应实际alarm revision+1；唯一(tenant_id,alarm_id,revision_after)，FK/范围/动作状态约束。

分页总量是实际轨迹行数。Coverage={kind:"FROM_CREATION"|"SINCE_MIGRATION"|"PARTIAL",first_revision:null|integer,journal_through_revision:null|integer,current_revision:integer,entry_total:integer,baseline_recorded_at:null|time}，从全部轨迹聚合而非当前页，空记录的first/journal_through均null。存在内部revision缺口或当前revision未覆盖时PARTIAL，空历史也PARTIAL，不画“完整”或自动补零；SINCE_MIGRATION不宣称过去齐全。FROM_CREATION须0号FIRE_CREATED且后续无缺口，不能凭最小revision0就猜。

当前alarm、聚合coverage、分页items在同一只读REPEATABLE_READ事务快照内查询，避免并发复核/恢复使当页与coverage当前版本互相打架。列表/API分页默认值沿用PageSizePolicy，按revision_after DESC,id稳定；SQL聚合/count，不把全部历史装内存做覆盖判定。详情是当前alarm读，轨迹after不是当前态。

## 权限与投影

新增alarm:history权限，适用PLATFORM/CUSTOMER；V8只补受保护bootstrap-admin PLATFORM根账户，旧普通账号不自动获得，客户可通过既有明确委派获得。查看轨迹必须当前alarm:read+alarm:history，查看当前详情只alarm:read；复核仍仅既有alarm:review，不因无新history权限被挡，历史按钮/请求独立。scope仍当前有效管理X-Tenant-Id或客户固定单位，伪造客户tenant头拒绝；当前session/账号/单位授权重验，跨单位/未知alarm404。worker-only仍503。

- GET /api/management/alarms/{id}、/api/customer/alarms/{id} -> 当前AlarmRow：id,tenant_id,device_id,device_name,address,event_type,occurred_at,physical_state,response_state,conclusion,revision,is_simulated。`tenant_id` 是记录所属单位，管理端详情、历史和复核使用该记录归属而非旧全局单位；未选全局单位也可从全部授权列表打开。快照名称/地址来自报警本身，不偷调设备/产品目录，也不请求原文；不新增remark/actor敏感字段。历史回执保持提交时快照，不补造旧记录字段。
- GET /api/{management|customer}/alarms/{id}/history?page=1&size=... -> {items,total,page,size,coverage:Coverage}。没有history权限403，当前详情照常可读；不以隐藏UI替代后端权限。

Entry公共字段：{id,alarm_id,action,revision_before:null|integer,revision_after:integer,before:null|State,after:State,occurred_at:null|time,recorded_at:time,actor_kind:"PLATFORM"|"CUSTOMER"|"SYSTEM"|"LEGACY_UNKNOWN",actor_label:string,is_self:boolean,remark:null|string,origin:"APPLICATION"|"MIGRATION_SNAPSHOT"}。
State={physical_state:"FIRE"|"NORMAL",response_state:"UNHANDLED"|"HANDLED",conclusion:null|"TRUE_FIRE"|"FALSE_ALARM"|"TEST"|"DEVICE_ALARM"}；before null只用于创建或基线，不当作先前正常。action=FIRE_CREATED|REVIEWED|ALARM_RECOVERED|DEVICE_RECOVERED|MIGRATION_BASELINE。
管理Entry另有actor_id:null|string、actor_name:null|string（复核时实际身份display name快照，不用以后用户改名覆盖）；客户投影完全不返回这两个key，不以null冒充裁剪。客户actor_label只“本人”（is_self）、“平台人员”、“单位人员”、“系统处理”、“历史未知”，不下发真实姓名/username/邮箱/电话/全局账号ID。管理显示快照名或类型，没有业务操作者的系统轨迹不能猜某个人。没有payload/raw_event、源凭据、任务lease、后台SQL或管理审计request_id。

REVIEWED.remark为该次复核正文（旧null/空兼容），历史读需要新明确权限才见备注；管理端grant相当于可读该单位授权报警的历史业务说明。MIGRATION_BASELINE.remark只表明迁移时末次备注，不宣称知道其产生人/时间。物理动作remark=null；如需说明固定在label，不复制未知原文。

## 写入与锁

alarm领域持有轨迹写服务（MANDATORY/普通回滚），其它领域只经现有AlarmLifecycle端口调用；不让device/processing跨写alarm_trace_entries、不引入alarm→processing/device包循环。

复核保持原auth/idempotency/CAS行为与返回形状；成功变更后在同事务追加REVIEWED、保存幂等结果/审计，任一失败全部回滚。actor snapshot用已核对当前身份，actor类型/名字由服务端生成，不受请求body覆盖。非法NUL/不配对surrogate备注应拒绝400，复用集中2000字符上界（旧null/空合法），不记录请求秘密。

fire插alarm后追加FIRE_CREATED；recoverSimulationAlarm原guard通过、持alarm锁后，取before/revision，改状态+revision再追加ALARM_RECOVERED；occurred_at为调用方传入的已限定平台模拟观测时间，不充当可信厂商设备钟。

旧recoverDevice先按id排序锁定所有当前FIRE报警、逐行改状态并各追加DEVICE_RECOVERED（不可用一条bulk UPDATE后丢失before）；保留P1按设备恢复语义，不用此批操作冒充P5单报警严格关联。旧端口没有事件时刻/actor上下文则occurred_at=null、SYSTEM，不伪造实际恢复发生时间；recorded_at为DB实际记录时间。只留已真的发生的变化，已NORMAL不重复恢复/加revision/轨迹。P5已锁device→alarm；review只actor/tenant→alarm，不添device反向锁。轨迹服务不提交独立事务。

旧同keyreview返回旧snapshot是P1兼容，不让新详情UI用它回滚物理当前状态；成功/重放后只重读当前详情及有权历史。并发复核/恢复按既有revision锁序，一个版本一条对应轨迹，409须刷新重开不能自行+1。

## 前端

双端报警表增加“查看详情”，只读打开不复核；支持已有alarm:review的人在详情启动原复核表单，成功后重拉当前态，不用旧review返回快照覆盖当前物理状态。history单独按alarm:history显示/请求，缺权限不偷偷取；GET403/撤权清旧历史并显示无权，不清仍合法会话；401按现有退出。

详情显示报警固有设备名/地址快照、当前三态信息（当前只实现物理/人工，通知/命令不可用明确标未接入，不画成功），明确“复核≠恢复”。历史按不可变before/after、动作、revision、真实recorded与可空occurred、actor安全投影和备注展示，不把轨迹after当当前值。coverage提示完整从创建/仅迁移以来/轨迹不完整；无旧历史不补造，翻页不隐藏coverage。

切单位/登出/关详情/选另一报警取消旧读取与销毁相关备注表单，迟到当前/历史/复核响应不污染新scope。actorName/remark等用React普通文本不innerHTML。未知enum/坏revision/异常Coverage不画正常/完整/0；新详细DTO白名单，customer不保留管理actor keys/原文。旧200测试保留；没有造联系人、通知回执、领取租约、硬件复位/消音按钮。

## 出口验证

V7→V8已有FIRE/HANDLED/备注/null数据迁移仅BASELINE并保持事实，首次fresh创建FROM_CREATION；新/旧数据状态不改。H2+PG17真实FIRE→复核(仍FIRE/HANDLED)→指定恢复(NORMAL/HANDLED)→再次复核，版本与四条history一致，重放/冲突不加history；旧device恢复多条每条留痕，不改变人工。history只读/分页/租户/客户actor裁剪/权限撤销/缺口PARTIAL；当前快照一致，名字改了不改旧actor快照。

审计/轨迹SQL失败事务完整回滚，旧key可在失败解除后同key重试；P5过期owner/末次CAS失败/写后audit失败不能留下成功轨迹，native多worker每alarm的FIRE_CREATED只有一条。实际Chrome双端详情/历史有无权限、复核仍物理FIRE、恢复后详情不被旧响应回滚、baseline/partial提示、分页/作用域销毁，完整包回归P1..P5a/P3b1。截图合成数据无秘密，不宣称生产/实际厂商或完整P6验收。
