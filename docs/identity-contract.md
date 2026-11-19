# 第二切片：正式单位与账号权限管理合同
> 本文保留历史合同或验收记录；原始报告、截图、JAR及未公开合同不随公开源码发布，文中历史结论不代表当前版本验收。

> 历史切片合同，保留当时API与验收口径，不是当前操作手册。当前业务写入使用有效登录会话并在事务内重查权限、范围与版本；不再提交或复核 `actor_password`。普通增改直接保存，重要操作明确二次确认；登录、自助改密和设置目标新密码仍保留真实凭据流程。下文的操作密码要求仅描述历史版本，当前说明见[当前范围说明](../README.md#当前范围)及[操作导航](../README.md#当前操作导航)。

## 核心不变量

- 单位即租户；CUSTOMER只能属于一个单位，PLATFORM的tenant_id必须null。
- 客户单位只能来自已认证会话；任何X-Tenant-Id依旧拒绝。
- 平台租户业务必须显式选择已授权单位；创建单位成功原子授予创建者访问，不给其他平台用户自动全局授权。
- 本轮管理仅覆盖当前单位内CUSTOMER账户；平台账户另有全局页面及权限。禁止在普通users请求中指定user_type/tenant_id或修改用户名/账户类型/租户。
- 权限白名单、受众分类、可委派范围由服务端返回。授予权限必须同时属于该账户类型的catalog和操作者当前权限；平台单位授权只能授出操作者已有grant。写事务内重新核对，不信任前端菜单。
- 管理目标上界：编辑或重置目标前，目标当前权限也必须为操作者权限子集，平台目标当前grant也必须为操作者grant子集；不可用重置更高权限/更宽单位范围的账户来接管其身份。跨租户先404，自己/根账户先409，超出管理上界403。此检查在目标行/管理协调事务内完成，拒绝不改密码/权限/状态/会话。
- 所有管理写操作要求当前操作者密码actor_password，且必须在事务内验证；不持久保存、不回显、不可放日志/URL。失败错误不得回显输入。
- 所有管理写操作均有Idempotency-Key与审计；更新/重置带expected_revision。相同键不同非秘密业务内容409；含目标新密码的幂等判等需慢哈希校验（PBKDF2随机盐），不可把密码放response_json或快速SHA-256指纹/审计。actor_password不进入持久指纹，但重试也重新验证。
- 不允许管理自己（自助改密继续走auth/change-password），不允许管理受保护bootstrap根账户。保护标记是数据库事实，不接受客户端指定；迁移识别既有bootstrap-admin，新建bootstrap也设保护。根账户仍只使用已授权单位，不拥有隐式全租户数据权限。
- 账号修改/启停/权限或grant变更/管理员重置密码均撤销目标全部会话、revision递增；重置后must_change_password=true。启用账号不恢复旧会话。
- 明确例外：创建单位时自动给创建者新增该新单位grant属于创建事务，不撤销创建者会话、不改其账号revision，也不视为根账户自管理。此增量必须经过units:write+actor_password验证；前端成功后刷新租户选择器。通过平台账号管理显式修改任何grant仍遵守全部会话撤销规则。
- 单位停用后客户登录/既有会话及任何租户业务均拒绝，撤销该单位客户会话；平台单位管理页面仍能查看/重新启用已授权单位。不要物理删除单位或账号。
- 安全管理命令使用数据库单例协调行（或经证明一致的锁顺序）串行化，避免两个管理员相互修改造成user-row死锁。普通报警事务不受此全局锁影响。

## 数据与权限

新增租户enabled/revision、用户revision/is_protected；只用追加Flyway迁移，不改V1/V2。迁移为既有受保护bootstrap根账户补充新管理权限，既有其他用户不悄悄提权。新bootstrap权限同步。演示客户维持原权限，由管理端显式授予用户管理能力。

新增权限：units:read、units:write；users:read、users:write、users:reset-password；platform-users:read、platform-users:write、platform-users:reset-password。CUSTOMER catalog只含summary:read、device:read、alarm:read、alarm:review、users三项；平台catalog包含现有与新增平台权限。根账户拥有全部支持权限。

所有ID是字符串、JSON snake_case、错误保持{code,message,request_id}。Unit={id,name,enabled,revision}。ManagedUser={id,username,name,user_type,tenant_id,enabled,must_change_password,revision,is_protected,permissions:string[],tenant_ids:string[]}，CUSTOMER的tenant_ids为空。任何响应都不得含密码/密码摘要/会话令牌。

分页保持{items,total,page,size}。keyword按字面搜索；未指定size遵守default_page_size，硬限制沿用集中策略。identity不能反向依赖configuration：如需读取默认分页，通过shared策略接口由configuration实现注入，不跨域写设置表。

## 端点

### 全局权限目录

GET /api/auth/permission-catalog -> {items:[{key,label,user_types:[PLATFORM|CUSTOMER],delegable:boolean}],password_policy:{min_length,max_length}}

登录且已改密后可调用；delegable依据当前DB权限。前端不自行发明权限值，普通客户不得看到不适用的可授予平台权限选项。

### 单位管理（仅管理端、无需X-Tenant-Id）

GET /api/management/units?page=1&keyword=：units:read，返回操作者已授权单位，包含停用状态。
POST /api/management/units：units:write，{name,actor_password} -> Unit，200；原子建单位+grant+审计+幂等。
PUT /api/management/units/{id}：units:write且grant，{name,enabled,expected_revision,actor_password} -> Unit。

原GET /management/tenants保持items列表，仅返回已授权且启用单位，供选择器使用；可加enabled/revision字段。

### 单位客户账号（双端、租户业务）

GET /api/{management|customer}/users?page=1&keyword=：users:read。
POST 同路径：users:write，{username,name,initial_password,permissions,actor_password} -> ManagedUser，200。
PUT .../users/{id}：users:write，{name,enabled,permissions,expected_revision,actor_password} -> ManagedUser。
POST .../users/{id}/reset-password：users:reset-password，{new_password,expected_revision,actor_password} -> ManagedUser。

路径目标必须为当前单位CUSTOMER，否则404，不泄露是否存在。拒绝自我管理409 SELF_MANAGEMENT_FORBIDDEN。

### 平台账号（仅管理端、无需X-Tenant-Id）

GET /api/management/platform-users?page=1&keyword=：platform-users:read，返回平台账号（密码不可见）。
POST 同路径：platform-users:write，{username,name,initial_password,permissions,tenant_ids,actor_password} -> ManagedUser。
PUT .../platform-users/{id}：platform-users:write，{name,enabled,permissions,tenant_ids,expected_revision,actor_password} -> ManagedUser。
POST .../platform-users/{id}/reset-password：platform-users:reset-password，{new_password,expected_revision,actor_password} -> ManagedUser。

必须限制当前操作者grant和权限子集；根账户/自我不可编辑或重置。平台账号可看平台用户基本列表仅由专门权限控制，不因普通users:read而泄露全平台信息。

## 前端

沿用Nova本地组件。新增单位管理、单位用户、平台用户三页；两页全局页不要求tenant；单位用户在管理端要求显式选择tenant、客户端用自己的tenant。按permissions与受众显示菜单/页面/动作，直接访问页面也不得发不该发的API。

表单：用户名/姓名、权限勾选、平台单位授权多选、启用状态、当前操作者密码；创建/重置的新密码和确认输入均不预填，成功后清空，关闭销毁。不得存入Zustand/localStorage/URL。启停、权限变更、重置含清楚的二次确认和撤销会话提示。自我与保护账户只读，后台仍强制。

409刷新后重新打开；失败重试保持同一业务尝试的幂等键，表单内容改变则换键（actor_password重输不改变业务尝试）。跨租户切换清除旧表格/抽屉/密码，防迟到结果串单位。单位停用成功刷新租户选择，原选项失效则清除，但全局单位页可重新启用。

## 验证出口

保留原22后端、16前端及HTTP/浏览器验证。新增：真实开户不依赖demo；权限子集/平台grant越权/非法权限；跨租户目标404；客户伪造租户头拒绝；自我/根账户保护；启停/改权限/重置即时会话撤销；重置强制改密；停用单位阻断客户与平台业务；重启/升级V3不丢已有数据、不扩大普通用户权限；revision和幂等（包括不同目标密码）冲突；并发管理员安全；审计和持久记录中不含秘密；UI隐藏不等于授权验证。真实PG可用时单独实测，不用H2结果冒充。
