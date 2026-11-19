# N05：产品版本电量阈值与设备覆盖（实施合同）

## 范围与量纲

固定参数`battery_low_threshold_percent`消费归一化指标`battery_percent`，单位`%`。范围0..100，精度沿用已有`NUMERIC(64,36)`，最多36位小数；前端保留原始JSON数值词法，不经浮点往返。比较符必须显式选`LT`或`LTE`，阈值与比较符同时有值或同时NULL。允许明确配置0/100，不代选20%或其他业务值。

初始未配置。此功能仅作读时电量阈值观察，不产生报警、通知、命令、物理状态或设备最后上报变更；也不实现30天画像。既有离线参数`DEVICE > TENANT > GLOBAL`、页面条数`GLOBAL_ONLY`及原有消费者保持不变，不把产品层暗插到旧离线参数家族。

## 版本与优先级

`DEVICE显式覆盖 > 设备钉住的已发布PRODUCT_VERSION阈值 > NOT_CONFIGURED`。

阈值属于产品版本草稿，现有复制版本操作复制它；发布后不可改。同一已发布版本不能受后来可变的“产品默认”影响，因此不建独立可变默认层。草稿修改经过原有密码、CAS、幂等和审计，版本revision递增、旧样例证据失效，发布仍要求当前版本完整通过的证据。版本读取增加必需的`battery_low_threshold`字段，未配置为NULL；编辑也显式提交该字段，NULL表示清除，所有活动调用者一起迁移。

有值版本阈值只允许`capabilities.battery=SUPPORTED`。设备覆盖允许已声明支持的绑定版本，或明确显示“无产品版本声明”的未绑定legacy设备；不冒称后者获得了产品支持证明。绑定PENDING/UNSUPPORTED时不接受有值覆盖。设备已有有值覆盖时，不得迁移到不支持/待确认电量的版本；须先显式清除，不自动丢弃配置。

## 实际消费者

设备列表、详情、筛选工作台共用单SQL关联投影，无N+1。有效阈值与当前存储电量在同一读快照计算：

- `NOT_CONFIGURED`：无有效阈值，不能推断正常或低电量。
- `NO_DATA`：有阈值但电量NULL，不能替代为0。
- `LOW`：有电量且满足显式LT/LTE比较。
- `NORMAL`：有电量但未命中比较；不表示设备物理状态正常。

返回阈值、比较符、`NONE|PRODUCT_VERSION|DEVICE`来源、来源ID及revision、数据库求值时刻。求值时刻不是设备上报时刻；数据不是硬件实时读数。

筛选工作台新增`battery_low/battery_normal/battery_not_configured/battery_no_data`四计数，按当前筛选、单位和设备范围统计所有匹配页，合计必须等于`device_total`。定义标识为`EFFECTIVE_BATTERY_LOW_THRESHOLD_AT_AS_OF_STORED_PERCENT`。不新增电量筛选条件，不改变summary语义。

## 权限与接口

产品版本仍使用管理端全局`products:read/write/publish`。`GET /api/management/metric-parameter-definitions`允许平台账号的`products:read`或`parameters:read`，不借单位目录权限。

设备阈值管理入口：`/api/management/devices/{id}/battery-low-threshold`；GET及`/history`需要`parameters:read`，PUT需要`parameters:read+write`，当前已授权/启用单位及当前`DeviceAccessScope`。不要求额外`device:read`，范围外404。参数管理的effective仅返回配置、configured与来源，**不返回LOW/NORMAL/NO_DATA、当前电量或上报时间**。完整观察状态只在本来要求`device:read`的设备投影中返回，客户只读且无管理历史/理由。

PUT提交`expected_revision,battery_low_threshold,reason,actor_password`和`Idempotency-Key`，返回完整`view,change`。初次revision0；SET/CLEAR每次+1，CLEAR保留tombstone，不能清除本来无值的覆盖来伪造历史。同key同业务返回原change与当前授权视图；改业务冲突。密码不进入幂等指纹或客户端持久化。审计失败回滚覆盖、历史和幂等记录。锁序遵循管理安全栅栏→actor→tenant→device→覆盖；配置不递增设备revision或修改设备事实。

前端确认后冻结目标、业务参数、revision、key；每次尝试后立即清密码。未知回执只能沿原意图重输密码确认，普通关闭/导航不能把未知结果洗成失败或可编辑新操作。授权/单位owner变化销毁旧状态，迟到旧响应不影响新owner；首次明确409需刷新重开。缺少完整合法新DTO不得冒成功。

## 验证状态

实施中，尚无N05通过声明。每片执行完整前后端测试与生产构建、关键迁移/安全回归、同源完整JAR的H2/PG17真实API和Chrome、桌面手机原图及退出/历史/宿主保护。长历史矩阵按已批准节奏保留在N07/M1，不删断言或放宽预算；功能验证不等于累计集成验收。仅本地受控合成，不接实际厂商、运营商、设备、短信语音或硬件下行。
