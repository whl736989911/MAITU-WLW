# P9a1：管理端SIM可插拔卡手工台账
> 本文保留历史合同或验收记录；原始报告、截图、JAR及未公开合同不随公开源码发布，文中历史结论不代表当前版本验收。

## 状态

**Frozen v1（2026-10-28）：Root已收BE/FE只读可实施性复核，日期/nullable/双权限/DTO/unknown与scope gate已统一。允许按独立写scope与任务依赖实现，不是代码/测试/运行通过。**用户要求继续开发；按[路线图](<roadmap.md>)及一期SIM约束推进。平台迁移已在Ubuntu有限完成，当前旧基线为9704/857/40；写前观察597源/方案文件与4原包SHA不回写。

## 1. 首片边界

- 仅管理端当前授权单位的可插拔SIM**手工信息登记**：创建、编辑、普通分页、详情与不可变维护记录。不是运营商台账认证、物理库存可用或实际绑定事实。
- 本片不做内置卡页面、卡设备绑定/解绑/回收、导入导出、运营商API、付费/续费、自动到期提醒、画像、批量与删除。无新后台任务、外部网络或服务。
- 卡形态仅REMOVABLE，**不据此推定可维护/可换、管理方或费用责任**；以上作为独立手工字段，允许UNKNOWN。手工状态不等于运营商当前状态；同步未实现，不用recorded_at冒最后成功同步。
- 不修改设备/其revision/最近上报/连接/物理/电量/产品绑定/主源、报警/轨迹/信号/通知/任务，不由欠费/到期手工信息推断离线或自动发送。
- 新业务数字无默认阈值。到期/证据日期由用户明确填写或null，普通分页消费现default_page_size；不加无人消费的提醒天数/配置。技术长度/JSON安全整数约束集中声明。

## 2. 身份与写事务

- 新PLATFORM-only `sim-cards:read`/`sim-cards:write`，当前启用单位grant仍独立。无客户路由/客户默认权，不以device:read/write替代SIM权限。
- V14仅对既有id=bootstrap-admin、PLATFORM且is_protected=TRUE的保护根账户幂等补这两权，沿V12模式；其它平台用户只经现委派权限/管理上界显式获得，不重置密码/会话/旧权。新初始化随集中PLATFORM_PERMISSIONS消费。
- 写/create/edit/replay必须同时具备当前`sim-cards:read`与`sim-cards:write`（完整回执含不可改ICCID），不自动授读，write-only403，不改变旧分组write-only语义。沿ManagementGuard当前身份/受众/单位/双权限/当前密码和安全协调锁，READ_COMMITTED、CAS与独立`sim-card-create`/`sim-card-edit`幂等namespace；fresh双权限验证在协调锁内、先于replay，密码不进fingerprint/报告/日志。未知写结果不自动重试。
- 同Tx写卡当前信息、追加唯一revision维护快照、既有idempotency与audit。任何history/audit/idem失败全回滚。新audit action沿旧UNCLASSIFIED投影，不扩原安全审计闭集或伪造完整取证。

## 3. 冻结模型

- V14新增`sim_cards`与`sim_card_changes`两表，46→48；V1..V13原byte不动，设备表/约束不动，不填种子卡。
- 卡id新小写完整UUID，tenant FK，tenant内ICCID精确唯一（不做跨单位全局查询/冲突泄漏）。ICCID仅手工原文标识，保字符串及前导0，不自动验证真实卡或运营商有效性；创建后不改，编辑同一卡的其它手工信息。
- 字段：iccid、carrier_name、replaceability、management_party、fee_responsibility、reported_operator_status、expires_on、evidence_at、source_note；卡形态REMOVABLE和来源MANUAL为本片固定事实。日期null表示未记录，不默认现在/永久；服务记录时间取数据库时间，和人工证据时间分开。
- party闭集UNKNOWN/PLATFORM/TENANT/MANUFACTURER/OTHER；replaceabilityUNKNOWN/REPLACEABLE/NOT_REPLACEABLE；reported_operator_statusUNKNOWN/ACTIVE/SUSPENDED/EXPIRED/CANCELLED/OTHER，仅手工报告，不自动推导。
- 技术上界ICCID64、carrier_name200、source_note1000 UTF16；拒NUL/坏UTF16，ICCID/carrier拒控制字符，Java边缘trim，不NFKC/数字转换。carrier_name/source_note始终string，Java边缘trim后允许空串表示未记录，不允许null。source_note可内部CR/LF/Tab，拒NUL/其它ISO控制/坏UTF16，普通文本展示；不提供URL/凭据/PIN/PUK/APN结构化字段，界面提示不得录入敏感凭据。自由备注可能含用户自行输入的信息，不据此认证全部notes无秘密；不把其内容复制到日志/普通审计/错误/报告。
- 卡独立revision 0..既有JSON安全整数上界。history包含每revision完整手工信息快照、DB记录时间及内部actor_id，不引用后续可变设备/用户事实作为过去；首片不公开用户id/username/密码/受限目录，录入人内部可追溯，audit仍受原audit权独立保护。
- history唯一(card_id,revision)、复合(card_id,tenant_id)同卡FK；仅追加，无改历史API，不宣称DB管理员防篡改。

## 4. 读/写接口

- `/api/management/sim-cards` GET列表（page/size/keyword）；POST创建。
- `/{id}` GET详情；PUT编辑。
- `/{id}/changes` GET维护快照分页。无跨单位全局接口/客户映射。
- GET严格RO/RR同快照、纯SQL分页，成功全schema零写，既有DENIED副作用单列；拒未知/重复query、正body/Transfer-Encoding。默认size来自集中设置，即使显式size也验证其元数据，坏源safe503不伪空/0。
- 新写局部strict JSON，拒重复/未知键、除两个明确日期外的null、词法数字coercion、错误Content-Type与写query；沿统一`{code,message,request_id}`安全错误，不返SQL/驱动异常/堆栈。
- 固定Card精确15字段：`id,tenant_id,iccid,carrier_name,installation_form,replaceability,management_party,fee_responsibility,reported_operator_status,expires_on,evidence_at,source_note,information_source,revision,recorded_at`。两个业务日期可null，其它非null；形态REMOVABLE、来源MANUAL固定。recorded_at为最近一次维护的数据库时间（沿DatabaseTime.now微秒），不是建档/运营商同步时间；原建档时间在history revision0保存。
- HistorySnapshot为上述15字段加`change_id,operation`（CREATE/EDIT），精确17字段；id仍是card id，不是change id。CardPage精确items/total/page/size四字段；ChangesPage精确card/items/total/page/size五字段，card是当前Card15对象，与history/items/total同RR快照；snapshot.id/tenant/iccid须同card，change_id完整小写UUID且同页唯一，revision同页唯一/DESC且≤card.revision，CREATE恰revision0、EDIT>0，不按recorded_at重排。所有公开数据不含actor用户目录；源异常/null/重复/资源超界不能返回似是而非的200。
- POST精确10字段：iccid、carrier_name、replaceability、management_party、fee_responsibility、reported_operator_status、expires_on、evidence_at、source_note、actor_password。PUT同样10字段但去iccid加expected_revision。所有键必须出现，只有两个日期允许null；carrier_name/source_note允许空串。expected_revision仅原始JSON安全整数，不接小数/exponent/字符串。idempotency header沿原规则，独立操作namespace。
- expires_on为`YYYY-MM-DD`真实Gregorian日历日期（0001..9999）或null，SQL DATE，无时区，不隐式合成午夜/日末。evidence_at输入为四位年份0001..9999 UTC `Z`、秒必需、最多6位小数或null；严格真实日历，不收offset/epoch/无时区字符串。evidence_at与recorded_at输出规范固定6位小数UTC Z，精度至微秒；前端不经Number/Date毫秒往返截断。未知绝不填当前/永久，不加未来/到期/滞后/提醒天数等未经确认业务限制。
- 列表只台账ICCID/carrier_name/source_note字面包含（沿LiteralLike，大小写不敏感、!/%/_非通配），不查设备/联系人/产品/全局单位目录；ORDER BY id ASC。详情无query；history只page/size，ORDER BY revision DESC，null不冒空信息。
- GET与两写成功精确200+相应DTO，不泛认其它2xx；创建回执revision0，编辑回执expected_revision+1且规范业务字段匹配。回执仅提交当时快照，清旧相关事实后让用户显式刷新，不能patch成现在。evidence输入`.1Z`与`.100000Z`统一规范fixed6Z构成同一canonical意图；字符串Java trim/空串/枚举/日期均先规范，fingerprint含tenant/op/target/revision/全部业务字段，不含密码/展示/DB记录时间；所有nullable fingerprint禁用会NPE的List.of。
- 新安全code：SIM_CARD_NOT_FOUND、SIM_CARD_READ_UNCONFIRMED、SIM_CARD_ICCID_CONFLICT、SIM_CARD_REVISION_EXHAUSTED；revision冲突沿REVISION_CONFLICT，旧身份/密码/拒绝code不改。不规范card UUID及跨单位不存在同404。仅真正命名ICCID唯一约束冲突映射ICCID_CONFLICT，不把所有23505/PK/FK误判。stored replay亦须strict完整形状/闭集/tenant/id/时间/数值/尾JSON验证，不从当前卡覆盖原receipt，不取新DB时间或追加历史。

## 5. 前端冻结边界

- 管理端仅当前sim-cards:read显示独立lazy入口；写按钮另需write，不为只写者暗授读。客户无入口或请求；不伪造内置卡tab或绑定按钮。
- 新组件复用现identity/scope/分页/tenant选择/样式；严格解码/完整body前后owner屏障；当前403不logout，旧scope body/reject/finally不得写新scope。
- 敏感写密码确认、busy防双击、CAS与原payload/idempotency。unknown/400/409仅原有效owner内保草稿与canonical意图，禁止自动重试/刷新；正常同owner同canonical创建即使取消/关闭/显式读刷新仍复用原key，密码不入fingerprint。409关闭重开不洗gate，必须显式成功重读当前详情revision（创建则成功列表重读）后允许重开，不能用读到相同字段证明旧未知写成功；history不得充当前CAS。
- 当前403/404是feature scope未确认，清全部当前数据/PW/表单草稿/intent并本地gate，不猜密码错、不logout；只有显式重读成功、严格DTO及完整owner/op核验完成才解除gate，建立新feature生命周期，不能在GET开始时解除。旧pending回调不得影响新生命周期，unknown key不承诺跨已销毁owner复用。完整当前owner的401才清session；换单位/撤权/离页/改密unmount强制销毁数据与PW/草稿/intent，不让旧reject/body/finally碰新owner。
- 日期是手工记录、未认证状态标签与“不改设备/未同步/无续费提醒”提示可见；技术约束不做成普通客户可任意放大的业务开关。

## 6. 旧测耦合：先RED再最窄派生

- BE只读确认：DeviceGroupMigrationTest的latest helper无target且exact46；当前DeviceGroupIntegrationTest也exact46。Root原device-groups-smoke有all46/other42表数及报告常量，audit-records-smoke亦exact46全public表。新增两表不能同时保latest版本与这些历史数字。
- 有效V14且旧原byte测试必须先真实RED，保日志/XML；不能用编译/DDL错误冒此反例。**Root已实际收齐此前置**：原始反例证据：新Ubuntu根只加94A7…V14，351旧backend路径前后byte一致、迁移成功；原37测试中3个46/48断言失败、0error/skip、真实Maven退出1，未冒通过。随后仅历史迁移专测helper显式target13，保其46语义；新SIM迁移独立覆盖V13→14/latest/重复与旧事实/权限增量不变。
- 当前latest integration/native全schema检查仅允许46→48、other42→44等**精确两新增表派生**的预期/报告适配；不改成>=、过滤新表、弱化全表hash、删断言/跳case。所有其它旧业务/权限/CAS/幂等断言原byte保留，不回写9704/857/40历史。

## 7. 实施与出口

Root独占scripts/根docs/集成验证与源范围守卫；BE独占backend，FE独占frontend。按本冻结DTO与窄共享行、任务依赖实现。新增architecture专测覆盖SIM写归属/无device等外域依赖，不给旧Map.of十项再塞第11项或改旧断言。每次成功规范化no-op编辑也增一revision/一history，同key重放不追加。所有原表事实不变的迁移比较仅精确扣除2条已授权root新权，密码/session/其它旧rights等仍必须不变。

SIM功能本身不新请求联系人/设备/产品/用户/单位目录；不改变旧shell对本来有tenants:read账号已有的授权单位selector请求。新最小sim读账户可手输单位ID，不隐式取得其它权限。新增lazy页面资产数以实际冻结为准，原eager JS含common的500000 bytes门不放宽，不拿旧46资产数强套新功能。Ubuntu独立新工作目录/非root工具，不改已验旧Linux目录源/原包，不操作现宿主PG18/DSH或系统服务。全量Maven/npm/新完整包及新H2/原生PG/真实Chrome与原40 flags、原图/失败/清理分别记录；不声明生产/远端CI/真实渠道/卡状态真实性/HA。
