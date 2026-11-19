# N11：可解释画像与趋势（Frozen v1，实施合同）

## 目标与边界

按**单位规则版本**在明确窗口内对设备做可解释统计，并保存不可变快照。不改报警、不停设备、不派发通知。

- 初始窗口 30 天、最小样本 3 为**显式初始值**，必须由有权限人员配置后才会产生画像；未配置不启用。
- 结果必须区分：`OK`（样本充足）/ `INSUFFICIENT`（样本不足）/ `MISSING`（窗口内未上报）/ `UNSUPPORTED`（产品未支持该指标）。
- 统计只来自已应用的模拟观测（`SIMULATED_APPLY`），不读真实厂商数据。

## 数据（V27）

- `device_battery_observations`：应用消息时同事务写入的观测（`battery_percent`、`observed_at`、`source_kind`）。
- `profile_rules`：单位级、**不可变版本化**（每次配置写入新 version，旧版本保留）。
- `device_profile_snapshots`：每次重算追加一行，含规则版本、窗口、状态、样本数与统计值。

## 计算

窗口 = `[computed_at - window_days, computed_at]`；样本为窗口内观测按时间升序。

| 条件 | 状态 | 统计 |
|---|---|---|
| 产品版本 `battery` 能力非 `SUPPORTED` | `UNSUPPORTED` | 不计算 |
| 窗口内无观测 | `MISSING` | 不计算 |
| 样本数 < `min_samples` | `INSUFFICIENT` | 不计算 |
| 其他 | `OK` | latest / min / max / mean（2 位小数） |

## API

| 方法 | 路径 | 权限 |
|---|---|---|
| GET | `/api/management/profile-rule` | `profiles:read` |
| POST | `/api/management/profile-rule` | `profiles:write`（密码二次确认） |
| GET | `/api/management/devices/{id}/profile` | `profiles:read`（无快照 404） |
| GET | `/api/management/devices/{id}/profile/history` | `profiles:read` |
| POST | `/api/management/devices/{id}/profile/recompute` | `profiles:write`（密码二次确认） |

## 边界

重算为**同步、单事务**执行并留历史行；异步重算队列与定时重算不在本切片，不伪造。
