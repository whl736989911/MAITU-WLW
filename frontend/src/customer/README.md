# Customer workspace（客户侧自有目录）

本目录只承载客户专属页面内容与布局。设计参照 [Oil UI 工作台指南](https://ui.oiloil.org/guide/dashboard-workspace-design/)：首屏先显示可打开的报警，不用宣传横幅或推断性 KPI；桌面「事件清单／设备概况」并排，手机按处置顺序堆叠。设备和报警保留原工作台作为唯一读写入口。

## 接线契约

`App.tsx` 仍由工作区整合方维护。以下只用于 `audience === 'customer'` 分支，保留 `allowedPage`、`AuthorizedReadScope` 和现有页面名称；管理端仍用原组件。

| App 路由 | 客户端使用 | 真实消费者与边界 |
| --- | --- | --- |
| `summary` | `@/customer/home` 的 `<CustomerHome/>` | `api.summary`/`api.alarms` 只读当前会话权限范围；复核使用既有 `AlarmReview`。不连接管理端补拉或推导离线。 |
| `devices`、`device-monitoring`、`parameter-monitoring` | `@/customer/workspace` 的 `<CustomerDevicesWorkspace>{当前路由原组件}</CustomerDevicesWorkspace>` | 只导航；原设备台账、详情、监测和登记保留真实 API、显式写目标及原有保护，不另建台账。 |
| `alarms`、`alarm-history`、`fault-history` | `@/customer/workspace` 的 `<CustomerAlarmsWorkspace>{当前路由原组件}</CustomerAlarmsWorkspace>` | 只导航；原值守、故障及历史共用查询，物理状态和人工处置仍独立。 |
| `my-profile` | 原 `<MyProfilePage onPasswordChange={…} onLogout={…}/>` | 按权限显示个人资料、单位设置、导入导出、充值入口；本单位信息来自授权位置树，位置数并非单位全部位置。微信绑定复用原组件。 |
| `account` | 原 `<AccountPage/>` | 本人 `/me`、`/me/messages`、`/me/sessions`；读、处理复用消息版本；撤销其他会话须确认，保留当前会话。 |
| `unit-settings` | 原 `<UnitSettingsPage notifications={…}/>` | 单位通信阈值预览不写，确认后用 CAS 和幂等键写；结果未知沿冻结请求重试。位置改名保留该记录原父与版本，整枝移动仍用独立预览和执行。 |

`messages.tsx` 保留为管理端原始报文追踪；客户站内消息仍在 `account`，不放开管理权限。未改共享全局样式、App 入口、后端 API 或数据库迁移。

本地验收使用 `127.0.0.1:43305` 指向 `127.0.0.1:43205` 与隔离 H2 数据。前端定向运行 `npm test -- src/customer src/test/customer-unit-summary.test.tsx src/test/customer-place-tree.test.tsx`；不使用任何生产数据、通知或厂商调用。
