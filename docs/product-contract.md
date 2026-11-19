# P3：产品版本、纯解析样例与正式设备开户合同

保持P1/P2全部端点与安全边界。本轮不执行JS/Python脚本、不接外部网络、不创建真实厂商适配器。解析仅对提交的合成JSON样例执行，不更新设备/报警/通知；发布通过只表示样例映射通过，不是厂商协议已验证。

## 领域与迁移

新增product领域，依赖configuration/identity/shared；device可以调用product公开PublishedVersionCatalog读取不可变版本快照，product不得引用device/alarm。各域仅写自己的表。追加V4，不改V1/V2/V3。为受保护bootstrap根账号补新权限，普通用户不自动提权。

权限：products:read、products:write、products:publish、device:write、device:migrate，仅PLATFORM catalog；原device:read双端不变。管理写操作继续actor_password二次验证、Idempotency-Key、revision、事务审计与当前DB授权检查。产品全局页不要求tenant，设备写入必须显式已授权且启用的tenant。客户本轮仅看单位设备，不开放开户/迁移。

## 产品与版本

Product={id,name,manufacturer,model,description,revision}；manufacturer/model是产品稳定标识，创建后不改。本轮只创建、读取产品，不做删除/停用/改名等额外生命周期。

Version={id,product_id,version_number,status:DRAFT|PUBLISHED,revision,schema,capabilities,parser_tested,published_at,parser_test_summary,created_at}。版本序号在产品行锁内分配，唯一(product_id,version_number)，不是可自由修改的业务参数。已发布版本及其测试证据不可修改；新版本不自动影响任何设备。

Schema采用纯声明JSON Pointer，结构：

```json
{"format":"JSON_POINTER_V1","event_pointer":"/event","event_mappings":[{"raw_value":"FIRE","event_type":"FIRE"},{"raw_value":"RECOVERY","event_type":"RECOVERY"}],"battery":{"pointer":"/battery","scale":1,"offset":0,"min":0,"max":100}}
```

battery可以null（此产品未提供电量）。所有数值变换由schema配置；battery结果是百分比，允许区间必须在0..100内且min<=max。scale须正数，offset可正负，全部用有限BigDecimal和集中资源上下界，拒绝超大数字/指数/无穷。原始数值只接受JSON number，不把字符串/空值/错误变0。变换后越界返回null及diagnostic，不截断/四舍五入冒充有效值。

JSON Pointer不得wildcard/递归/脚本；必须合法RFC6901，长度、深度、样例大小、测试数、映射数均集中限制。输入解析限制嵌套深度/字符串长度/数字长度，拒绝重复JSON键，不通过覆盖重复键制造歧义。raw_value只允许非null字符串/布尔/数值，按JSON类型和值精确匹配；字符串"1"与数字1不同。重复raw_value映射、缺FIRE/RECOVERY映射、未知schema字段均拒绝。未映射、缺失、null事件一律UNKNOWN，不归正常/恢复。

Capabilities={fire:SUPPORTED|UNSUPPORTED|PENDING,recovery:同上,battery:同上}。本轮发布要求fire/recovery=SUPPORTED且实际有对应映射；battery=SUPPORTED必须有battery配置，其它状态不输出可用电量。它们只是当前模板声明，不是验证厂商硬件能力。

样例={name,case_kind:FIRE|RECOVERY|UNKNOWN|MALFORMED,payload:string,expected_event:FIRE|RECOVERY|UNKNOWN,expected_battery_percent:number|null}，必须是明确合成、无真实凭据的样例。MALFORMED为无法解析的字符串，结果UNKNOWN并有解析错误诊断。所有样例受体积/数量限制，不能包含明显password/token/secret/authorization字段；UI提示不粘贴生产密钥/个人资料，不声称这能识别所有秘密。

parser_test_summary固定{passed,tested_revision,schema_fingerprint,samples,results}，tested_revision记录保存证据后的revision；发布只改生命周期不改指纹，发布后保留通过证据。parser_tested只在当前指纹样例全过且覆盖充分时true。

test返回{version_id,revision,passed,results:[{name,passed,event_type,battery_percent,raw_event,diagnostics:string[]}]}；覆盖不足时整体passed=false（单个结果仍可passed=true）；malformed同样200业务测试结果而非服务器500。结果保留raw_event，未知不变正常。测试不提交设备/报警/事件记录。测试证据与当前schema/capabilities指纹绑定，草稿编辑立即清除旧通过证据；发布必须当前revision拥有全部通过且覆盖FIRE/RECOVERY/UNKNOWN/MALFORMED四类，至少一个真实电量有效样例（battery支持时）和一个缺失/无效电量=>null的样例。保存样例及结果以复验，但仅用于明确合成输入。

## 产品端点（管理端，全局）

GET /api/management/products?page=1&keyword= -> Page<Product>，products:read。
POST 同路径 {name,manufacturer,model,description,actor_password} -> Product，products:write。
GET /api/management/products/{id}/versions -> {items:Version[]}，products:read。
POST .../versions {base_version_id:null|string,actor_password} -> Version，products:write；null生成上面规范的可编辑默认草稿和PENDING能力，不自动生成通过证据。非null必须同产品，复制成新草稿，不复制发布/测试通过状态。
PUT /api/management/product-versions/{id} {expected_revision,schema,capabilities,actor_password} -> Version，products:write且DRAFT。
POST .../{id}/test {expected_revision,samples,actor_password} -> 测试结果，products:write且DRAFT。成功保存测试证据时revision递增一次，返回revision；失败结果也递增并记录，发布必须取最新revision。
POST .../{id}/publish {expected_revision,actor_password} -> Version，products:publish且DRAFT，必须有效证据。发布revision递增并不可逆；重试同key返回原结果，不能再次写入。发布后test/edit也409 VERSION_IMMUTABLE（可读取原证据），不能偷偷变更已发布版本。

所有写操作200、错误与P2一致，重复/冲突409，非法结构400；错误不得泄露payload/actor_password。产品不存在404。列表分页使用集中默认和边界，不在前端散落条数。

## 正式设备

DeviceRow保留原字段，增加product_id,product_version_id,product_name,version_number,revision,integration_state。旧种子未绑定版本时字段null、integration_state=LEGACY_SIMULATION，明确“未绑定模板”，不可冒充正式产品。新设备is_simulated=false默认，integration_state=PENDING_INTEGRATION，last_message_at与battery_percent=null、connectivity=UNACTIVATED、physical_state=UNKNOWN，创建本身不是一次设备上报。

V4必须使devices.physical_state支持UNKNOWN，不能把新设备落库为NORMAL再仅UI遮掩。可用兼容H2/PG的rename旧列→新增正确约束列→复制旧状态→drop旧列方式移除旧匿名CHECK；旧NORMAL/FIRE数据不变。报警表仍只记录已发生火警状态，不为开户创建报警。

POST /api/management/devices {name,device_eui,address,place_id,product_version_id,is_simulated:false|true,actor_password} -> DeviceRow，device:write；仅已发布版本可注册；EUI/标识继续平台唯一、安全ASCII上限64，不假设所有品牌都16位十六进制。默认真实设备，模拟标记必须明确创建且此后不可改。自2026-11-05起，`place_id` 必填、最长64字符，必须指向当前单位的既有位置；HTTP 与服务层均拒绝省略、null、空白、超长、不存在及跨单位位置，不创建设备、成功回执或绑定历史，也不自动选择默认位置。有效位置写入设备行，并参与开户幂等指纹。创建事务写一条from_version_id=null、to_version_id=所选版本、reason=REGISTER的初始绑定历史；旧种子不补造未知历史。不能通过模拟入口更改真实设备，既有门禁保留。
PUT /api/management/devices/{id} {name,address,place_id?,expected_revision,actor_password} -> DeviceRow，device:write；允许同单位内调整所属位置；省略位置时保留原值，不清空位置。不改EUI/tenant/版本/物理状态/来源标记。数据库非空迁移不在本次开户合同切换范围。
POST /api/management/devices/{id}/migrate-version {product_version_id,expected_revision,reason,actor_password} -> DeviceRow，device:migrate；目标必须PUBLISHED，同产品版本（旧未绑定演示设备允许显式首次绑定），同版本请求409。必须写版本绑定历史（from/to/actor/时间/reason），事务内CAS。
GET /api/management/devices/{id}/version-history -> {items:[{id,from_version_id,to_version_id,reason,actor_id,occurred_at}]}，device:read+当前tenant；不返回其它单位对象。

设备revision统一代表整行：每次新模拟消息改变物理状态/最后上报时间也递增，重复消息不递增；编辑/迁移需重新读取当前revision，冲突必须刷新重开。

迁移仅更新绑定/版本快照与revision，绝不改physical_state/last_message_at/battery/旧事件/报警或生成通知。既有报警的名称/地址快照保持不变。不得批量静默切最新版本。新产品本轮可开户，但未有真实接入，UI显示“待接入联调”，不声称已连通品牌。

## 位置变更历史（2026-11-05）

- `GET /api/management/devices/{id}/place-history?page=1&size=…`：管理端 `device:read`，按当前单位授权与设备范围校验；客户令牌不能使用管理端接口。`X-Tenant-Id` 可省略以支持“全部单位”视图，提供时仍限制到该单位。未知设备、跨单位或设备范围外对象返回404，不用空列表掩盖拒绝；无权限403，无会话401。
- 仅接受单值 `page`、`size`，复用集中默认页大小和分页上限；非法、重复及未知参数返回400。响应为 `{items,total,page,size}`，按设备 `revision_after` 倒序稳定分页，不依赖时间戳是否相同。
- 每条记录含 `id,action,from_place_id,from_place_name,to_place_id,to_place_name,revision_before,revision_after,actor_id,occurred_at`。`REGISTER` 为正式开户的初始位置（包括调用开户服务的批量导入），原位置与原revision为null、新revision为0；`MOVE` 只在同单位实际改变 `place_id` 时记录。名称是当时位置节点的名称快照，不是动态查询结果，也不是完整祖先路径快照。
- 位置记录与设备写入、审计、幂等回执在同一事务；任一失败整体回滚。重放、仅修改名称/地址、提交相同位置不增加位置移动记录。设备整行revision仍按原规则递增，历史中的revision允许有间隔。
- V42新增 `device_place_history`，不回填历史设备，不从当前位置推断旧归属；旧设备首次补位置时原位置可以为null。位置名称变化、原位置被删除均不重写历史记录。历史记录不使用指向位置表的外键，但保留设备/单位归属外键。
- 设备监测行操作新增“位置历史”，提供空态、错误重试、刷新与服务端分页。默认全部单位时可直接读取已授权设备历史，桌面及手机均使用同一历史数据。
- 本次不实现跨单位移动、位置树上级变更历史、用户位置子树授权或报警位置快照；不改写物理状态、上报时间、版本绑定、旧报警，也不生成设备消息或下行命令。

## 前端

增加管理端“产品模板”全局页，产品创建、版本列表/草稿编辑（可先结构化JSON编辑器）、合成样例测试结果、明确发布确认。能力三态独立显示，PENDING不能画成支持；错误/未知/缺失电量真实显示。样例编辑不预填任何真实厂商代码，用上述合成FIRE/RECOVERY样例；完整四类测试可提供示例集合，但必须用户显式运行并检查。

设备页管理端按权限提供开户、元数据编辑、版本迁移/历史抽屉；客户无写动作。产品/已发布版本从真实API选择，开户必须显式选择具体版本，不能默认“永远最新”。actor_password只在二次确认步骤，本地表单状态、关闭/切tenant销毁、失败重输不换同一业务key。409刷新重开，复用P2敏感动作机制。新设备UNKNOWN/未激活/待联调不画绿正常。

## 验证出口

保留34后端/30前端及P1/P2黑盒。追加：无效/超限/重复键/类型混淆/未知事件/0电量与null、测试无业务副作用、证据编辑即失效、无测试或缺覆盖不发布、发布不可变、并发发布/克隆序号、仅published开户、真实设备模拟拒绝、版本钉住与显式迁移、迁移不改物理/旧报警、跨tenant/权限/停用单位门禁、旧数据V4升级。Lead跑完整JAR、H2及原生PG17和真实浏览器，不用模拟协议声称接通真实品牌。

## 同单位位置整树移动与便捷维护（2026-11-11）

新增管理端 `GET /api/management/places/{id}/move-preview?target_parent_id=…` 与 `POST /api/management/places/{id}/move`。明确 `X-Tenant-Id`，同时具有 `units:read`、`units:write` 和该单位完整设备范围；客户令牌不能借此调用管理接口。目标父位置省略/空白表示本单位根层；自身、后代或外单位父位置拒绝。

预览返回 `{place,target_parent,places_count,devices_count,snapshot}`；位置数包含自身和全部后代，设备数由后台完整集合计算，包含挂载在后代及已归档的设备，不是当前页统计。快照覆盖子树成员/版本、设备挂载/版本及原/目标祖先链。提交体为 `{target_parent_id,expected_revision,snapshot,reason,actor_password}`，另需 `Idempotency-Key`。返回 `{place,places_count,devices_count,changed}`。

提交在既有管理写锁下重新核验权限、密码、版本和快照；范围变更拒绝并要求重读，不半搬子树。相同请求重放先取原回执，不用已变化的当前快照误拒重放；同父目标不增加位置版本。仅改根节点父关系，设备ID和 `place_id`、旧报警、设备位置履历与授权保持不变；有审计及幂等回执，不伪造每台设备物理移位。既有位置元数据更新若改变父位置，同样要求完整设备范围，普通改名不额外要求设备全量权限。

页面就近新增下级、编辑及删除空位置；改父关系使用独立整体移动预览。设备行单台移位仍通过既有 `batch-move` 事务写真实位置履历，筛选和页码保留。用户设备范围沿既有 `ALL_TENANT`/`DEVICE_SET` 合同，用分页检索/位置筛选选择明确设备，固定集合不跟随树变化自动扩权。跨单位整棵位置树移交不是本次同单位移动接口的能力，不能以修改父ID替代。

