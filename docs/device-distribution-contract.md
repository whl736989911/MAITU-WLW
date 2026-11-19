# N18：管理端经纬度分布图（Frozen v1，实施合同）

## 目标与边界

管理端维护设备经纬度并以**无底图**散布图呈现。

- 坐标**只由人工维护**：不推断、不取地址地理编码、不接在线/离线地图服务。
- 不引入建筑楼层体系；客户界面仍只显示文字地址。
- 坐标成对保存；缺一不允许。范围：纬度 ±90、经度 ±180（保留 6 位小数）。
- 写入需 `device:write` + 当前密码 + CAS `expected_revision`；清除即置空。

## 数据（V30）

`devices.latitude` / `devices.longitude`（`NUMERIC(9,6)`，可空），约束：
`(latitude IS NULL AND longitude IS NULL) OR (两者非空且在范围内)`。

## API（管理端）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| PUT | `/api/management/devices/{id}/location` | `device:write` | `{latitude, longitude, expected_revision, actor_password}` |
| POST | `/api/management/devices/{id}/location/clear` | `device:write` | `{expected_revision, actor_password}` |
| GET | `/api/management/devices/distribution` | `device:read` | 点位 + 已维护/未维护计数 + 语义 |

响应要点：

```json
{
  "points": [{ "id": "...", "name": "...", "address": "...", "physical_state": "UNKNOWN", "latitude": "31.234567", "longitude": "121.456789" }],
  "with_coordinates": 1,
  "without_coordinates": 2,
  "semantics": "坐标由人工维护，不推断；无底图，不引入地图服务或建筑楼层体系。客户界面仍只显示文字地址。"
}
```

## 界面

新增「设备分布」页：无底图 SVG 散布（点击 tip 显示名称与坐标）、点位表格、以及按设备选择维护/清除坐标的表单。
