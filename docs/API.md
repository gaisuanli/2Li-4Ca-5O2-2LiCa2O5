# 接口说明

## 1. 通用约定

- 后端基址：`http://127.0.0.1:8080/api`
- 通常请求与响应为 `application/json; charset=UTF-8`；`GET /alarms/export` 例外，返回 `text/csv; charset=UTF-8` 原始文件字节。
- 除登录、健康检查和 WebSocket 握手外，REST 请求均需发送 `Authorization: Bearer <token>`。
- 时间使用 ISO 8601；应用默认时区为 `Asia/Shanghai`。
- 客户端可发送 `X-Trace-Id`，最长取 64 字符；若未发送则服务端自动生成。

统一成功响应：

```json
{
  "success": true,
  "code": "OK",
  "message": "操作成功",
  "data": {},
  "traceId": "0b6e39c4c0da4b2e9fdaf8ecce21c942",
  "timestamp": "2026-07-20T02:30:00Z"
}
```

统一失败响应：

```json
{
  "success": false,
  "code": "VALIDATION_ERROR",
  "message": "设备编号不能为空",
  "data": null,
  "traceId": "0b6e39c4c0da4b2e9fdaf8ecce21c942",
  "timestamp": "2026-07-20T02:30:00Z"
}
```

常用 HTTP 状态：`400` 参数/JSON 错误、`401` 未登录或 Token 失效、`403` 角色或工地范围不足、`404` 资源不存在、`409` 状态冲突或唯一键冲突、`500` 未预期错误。

## 2. 认证与权限

角色代码：`ADMIN`、`SUPERVISOR`、`DEVICE_MANAGER`。没有在接口表中单独标记角色的业务接口，仍要求任意有效登录态及相应工地范围。

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/auth/login` | 公开 | 登录并返回 Token、过期时间和用户信息 |
| GET | `/auth/me` | 登录 | 获取当前会话用户 |
| POST | `/auth/refresh` | 登录 | 轮换当前 Token；旧 Token 立即失效，返回新的 Token、过期时间和用户信息 |
| POST | `/auth/logout` | 登录 | 注销当前 Token，并写审计日志 |
| GET | `/health` | 公开 | 服务与数据库健康检查 |

登录请求：

```json
{ "username": "supervisor", "password": "Safe@123" }
```

会话保存在后端内存中，默认 480 分钟过期；后端重启后原 Token 立即失效。刷新接口使用当前 Bearer Token，不需要额外请求体，并会写入 `TOKEN_REFRESH` 审计记录。

## 3. 分页约定

分页接口使用以下查询参数：

| 参数 | 类型 | 默认值 | 规则 |
| --- | --- | --- | --- |
| `page` | 整数 | 1 | 从 1 开始；小于 1 时偏移量按第 1 页处理 |
| `pageSize` | 整数 | 20 | 服务端限制为 1–100；PC 端提供 10/20/50 |

分页数据结构：

```json
{
  "items": [],
  "total": 42,
  "page": 2,
  "pageSize": 10
}
```

当前分页接口：`GET /devices`、`GET /risks`、`GET /alarms`、`GET /rules`、`GET /cameras`、`GET /sprinkler-tasks`、`GET /telemetry/history`、`GET /users`、`GET /audit-logs`、`GET /agent/conversations`、`GET /agent/conversations/{id}/messages`。

## 3.1 管理员用户管理

全部 `/users` 接口仅允许 `ADMIN`。列表项为 `id`、`username`、`displayName`、`role`、`siteScope`、`enabled`、`createdAt`。

| 方法 | 路径 | 参数或请求体 | 说明 |
| --- | --- | --- | --- |
| GET | `/users` | 可选 `keyword`、`page`、`pageSize` | 按用户名或显示名称搜索并分页 |
| POST | `/users` | 用户创建请求 | 创建时使用 BCrypt 保存密码并默认启用 |
| PATCH | `/users/{id}/enabled` | `{ "enabled": false }` | 启停用户；不能停用当前登录账号 |
| POST | `/users/{id}/reset-password` | `{ "password": "NewSafe@123" }` | 重置密码并清理该用户所有现有会话 |

创建示例：

```json
{
  "username": "safety01",
  "password": "SafeUser@123",
  "displayName": "一号安全员",
  "role": "SUPERVISOR",
  "siteScope": "1"
}
```

用户名只允许字母、数字、点、下划线或连字符，长度 3–50；角色只允许三种既有角色；`siteScope` 为逗号分隔的正整数工地 ID，服务端会排序、去重并验证工地存在。管理员只能查看和管理其自身全部授权工地范围内的账号，也不能创建超出自身范围的账号；非法的历史工地范围按安全默认隐藏并拒绝修改。密码至少 8 位，且 BCrypt 限制其 UTF-8 编码不超过 72 字节。

主要错误码：`USERNAME_EXISTS`、`INVALID_ROLE`、`INVALID_SITE_SCOPE`、`INVALID_PASSWORD`、`USER_NOT_FOUND`、`CANNOT_DISABLE_SELF`。新增、启停和重置密码均写审计日志。

## 4. 工地与区域

| 方法 | 路径 | 参数 | 返回 |
| --- | --- | --- | --- |
| GET | `/sites` | 无 | 当前用户可访问的工地列表 |
| GET | `/sites/{siteId}/zones` | 路径 `siteId` | 区域、场景坐标、设备总数和在线数 |
| GET | `/zones/{zoneId}/devices` | 路径 `zoneId` | 区域内设备列表 |

工地范围不足返回 `403 SITE_SCOPE_DENIED`。

## 5. 综合首页

| 方法 | 路径 | 查询参数 | 返回 |
| --- | --- | --- | --- |
| GET | `/dashboard` | `siteId=1` | 设备/在线/告警/风险摘要、等级分布、最近告警、设备类型和七日风险趋势 |

`summary.dataLabel` 会按配置明确标记“演示数据”或“业务数据”。七日风险趋势按当前数据库中的 AI 风险记录聚合，`statisticsAt` 取相关业务表的最新时间，不再返回固定时点。

## 6. 设备

| 方法 | 路径 | 权限 | 参数或请求体 |
| --- | --- | --- | --- |
| GET | `/devices` | 登录 | `siteId`；可选 `zoneId`、`type`、`status`、`keyword`、`page`、`pageSize` |
| GET | `/devices/{id}` | 登录 | 路径 `id` |
| POST | `/devices` | ADMIN、DEVICE_MANAGER | `DeviceRequest` |
| PUT | `/devices/{id}` | ADMIN、DEVICE_MANAGER | 完整 `DeviceRequest`；更新编号、名称、类型、工地、区域、位置和配置 JSON |
| PATCH | `/devices/{id}/enabled` | ADMIN、DEVICE_MANAGER | `{ "enabled": true }` |
| PATCH | `/devices/{id}/connection` | ADMIN、DEVICE_MANAGER | `{ "status": "ONLINE" }` |

新增设备示例：

```json
{
  "code": "ENV-101",
  "name": "北门环境监测站",
  "type": "ENVIRONMENT",
  "siteId": 1,
  "zoneId": 1,
  "location": "北门围挡内侧",
  "configJson": "{\"metrics\":[\"pm25\",\"noise\"]}"
}
```

常见类型：`TOWER_CRANE`、`ELEVATOR`、`FORMWORK`、`FOUNDATION_PIT`、`ENVIRONMENT`、`SPRINKLER`、`CAMERA`。连接状态写接口只接受 `ONLINE` 或 `OFFLINE`，其他值返回 `400 INVALID_CONNECTION_STATUS`。

`POST` 与 `PUT` 均校验设备类型、区域所属工地和 `configJson`；`configJson` 必须是 JSON 对象，重复编号返回 `DEVICE_CODE_EXISTS`。

## 7. 遥测

| 方法 | 路径 | 权限 | 参数 |
| --- | --- | --- | --- |
| POST | `/telemetry` | ADMIN、DEVICE_MANAGER | 遥测请求体 |
| GET | `/telemetry/latest` | 登录 | `deviceId` |
| GET | `/telemetry/history` | 登录 | `deviceId`、`metric`；可选 `from`、`to`、`page`、`pageSize` |
| GET | `/telemetry/trend` | 登录 | `deviceId`、`metric`；可选 `limit`，最大 500 |

遥测请求：

```json
{
  "protocolVersion": "1.0",
  "messageId": "DEMO-20260720-0001",
  "deviceCode": "TC-001",
  "messageType": "telemetry",
  "collectedAt": "2026-07-20T10:30:00+08:00",
  "metrics": [
    { "code": "windSpeed", "value": 11.8, "unit": "m/s" },
    { "code": "weight", "value": 45.2, "unit": "t" }
  ]
}
```

`protocolVersion` 只支持 `1.0`，`messageType` 只支持 `telemetry`。`collectedAt` 必须带时区偏移，且不能早于服务端当前时间 30 天或晚于 5 分钟，否则返回 `400 TELEMETRY_TIME_INVALID`。

支持指标及允许范围：

| 指标 | 范围 | 指标 | 范围 |
| --- | --- | --- | --- |
| `weight` | 0–300（塔吊） | `windSpeed` | 0–80（塔吊） |
| `rotation` | -360–360（塔吊） | `height` | -20–1000（塔吊） |
| `amplitude` | 0–200（塔吊） | `moment` | 0–10000（塔吊） |
| `obliquity` | -30–30（塔吊） | `pm25` | 0–2000（环境） |
| `pm10` | 0–3000（环境） | `noise` | 0–180（环境） |
| `temperature` | -60–100（环境） | `humidity` | 0–100（环境） |
| `load` | 0–10000（升降机） | `floor` | -10–300（升降机） |
| `speed` | 0–20（升降机） | `direction` | 0–3（升降机） |
| `doorStatus` | 0–1（升降机） | `limitStatus` | 0–1（升降机） |
| `axialForce` | -100000–100000（高支模/基坑） | `settlement` | -1000–1000（高支模/基坑） |
| `displacement` | -1000–1000（高支模） | `pressure` | 0–10000（高支模） |
| `horizontalDisplacement` | -1000–1000（基坑） | `waterLevel` | -1000–1000（基坑） |
| `earthPressure` | 0–10000（基坑） | `strain` | -100000–100000（基坑） |
| `settlementRate` | -1000–1000（基坑） | `xAngle` / `yAngle` | -30–30（高支模/基坑） |

成功数据包含 `messageId`、新增指标数、重复指标数和新建告警 ID。相同 `messageId + metricCode` 再次上报不会重复入库。历史接口按采集时间和记录 ID 倒序分页，`from`、`to` 使用 ISO 8601 本地日期时间且包含边界；开始时间晚于结束时间时返回 `400 INVALID_TIME_RANGE`。趋势接口先截取最近 `limit` 个点，再按采集时间升序返回，便于直接绘图。

## 8. 告警规则

| 方法 | 路径 | 权限 | 请求体 |
| --- | --- | --- | --- |
| GET | `/rules` | ADMIN、SUPERVISOR | 必填 `siteId`；可选 `keyword`、`enabled`、`sourceType`、`metricCode`、`page`、`pageSize` |
| POST | `/rules` | ADMIN、SUPERVISOR | `RuleRequest` |
| PUT | `/rules/{id}` | ADMIN、SUPERVISOR | 完整 `RuleRequest` |
| PATCH | `/rules/{id}/enabled` | ADMIN、SUPERVISOR | `{ "enabled": false }` |

规则请求：

```json
{
  "name": "塔吊风速高值预警",
  "sourceType": "DEVICE_RULE",
  "metricCode": "windSpeed",
  "operator": ">",
  "thresholdValue": 10,
  "severity": "HIGH",
  "scopeType": "DEVICE",
  "scopeId": 1,
  "suppressionSeconds": 300
}
```

运算符：`>`、`>=`、`<`、`<=`、`=`；等级：`LOW`、`MEDIUM`、`HIGH`；范围：`SITE`、`DEVICE`、`TYPE`；抑制时间最少 30 秒。环境指标必须使用 `ENVIRONMENT_RULE`，塔吊、升降机、高支模和深基坑指标使用 `DEVICE_RULE`。`SITE` 的 `scopeId` 是授权工地 ID；`DEVICE` 的 `scopeId` 是设备 ID，并校验设备所属工地及指标对应类型；`TYPE` 的 `scopeId` 实际保存所属工地 ID，目标设备族由指标唯一推断。单工地账号创建 `TYPE` 规则时可省略 `scopeId`。`axialForce`、`settlement`、`xAngle` 和 `yAngle` 同时适用于高支模与深基坑，不能唯一推断 TYPE，创建这四类规则时必须使用 `DEVICE` 范围，否则返回 `400 RULE_METRIC_TYPE_AMBIGUOUS`。

演示数据会幂等补齐施工升降机载荷/限位、高支模位移/压力、深基坑水平位移/沉降速率共六条默认规则。超过阈值的遥测沿用同设备、同规则、未关闭告警的抑制窗口合并逻辑。

## 9. 告警中心

| 方法 | 路径 | 权限 | 参数或请求体 |
| --- | --- | --- | --- |
| GET | `/alarms` | 登录 | `siteId`；可选 `zoneId`、`from`、`to`、`status`、`severity`、`source`、`keyword`、`page`、`pageSize` |
| GET | `/alarms/export` | 登录 | 与列表相同的筛选条件；返回带 UTF-8 BOM 的 CSV |
| GET | `/alarms/{id}` | 登录 | 告警详情及动作时间线 |
| POST | `/alarms/{id}/actions` | ADMIN、SUPERVISOR | `{ "action": "CONFIRM", "note": "现场已核查" }` |

告警状态机是严格单向的：

| 动作 | 原状态 | 目标状态 |
| --- | --- | --- |
| `CONFIRM` | `PENDING` | `PROCESSING` |
| `RESOLVE` | `PROCESSING` | `RESOLVED` |
| `CLOSE` | `RESOLVED` | `CLOSED` |

越级、倒退或重复执行返回 `409 INVALID_ALARM_TRANSITION`。该约束针对人工动作接口；来源为 `SYSTEM_DEVICE_OFFLINE` 的系统告警由心跳服务维护：超时后创建或合并为待处理告警，遥测恢复时自动进入 `RESOLVED`，再次离线时会合并次数并重新进入 `PENDING`。告警查询可使用 `source=SYSTEM_DEVICE_OFFLINE` 精确筛选，也可使用 `source=SYSTEM` 匹配全部系统来源。`from`、`to` 按最近发生时间过滤并包含边界；导出复用列表的校验和筛选逻辑。

## 10. 视频与 AI 风险

| 方法 | 路径 | 权限 | 参数或请求体 |
| --- | --- | --- | --- |
| GET | `/cameras` | 登录 | `siteId`；可选 `zoneId`、`online`、`keyword`、`page`、`pageSize` |
| GET | `/risks` | 登录 | `siteId`；可选 `status`、`page`、`pageSize` |
| POST | `/risks` | ADMIN、DEVICE_MANAGER | AI 风险写入请求；按 `cameraCode` 推导工地和区域 |
| POST | `/risks/{id}/review` | ADMIN、SUPERVISOR | `{ "action": "CONFIRM", "note": "现场确认" }` |

AI 风险写入示例：

```json
{
  "cameraCode": "CAM-001",
  "riskType": "未佩戴安全帽",
  "confidence": 0.934,
  "modelVersion": "yolo-sitesafe-1.0",
  "occurredAt": "2026-07-20T10:30:00+08:00",
  "evidenceUrl": null
}
```

`confidence` 必须位于 `0–1`；风险类型和模型版本最长 64 个字符，证据地址最长 500 个字符。摄像头不存在返回 `404 CAMERA_NOT_FOUND`，当前用户无权访问该摄像头所属工地时返回 `403 SITE_SCOPE_DENIED`。写入成功后的初始状态为 `PENDING_REVIEW`，并发布 `risk.created` 实时事件。

风险复核动作只允许 `CONFIRM` 和 `FALSE_POSITIVE`。确认有效会创建一条 `HIGH`、来源为 `AI_RISK` 的告警；已复核记录不能再次复核。

摄像头的 `playbackStatus` 为 `NOT_CONFIGURED`、`READY` 或 `OFFLINE`。演示数据没有真实流地址时明确返回 `NOT_CONFIGURED`。

## 11. 环境与喷淋

| 方法 | 路径 | 权限 | 参数或请求体 |
| --- | --- | --- | --- |
| GET | `/environment/summary` | 登录 | `siteId` |
| GET | `/environment/trend` | 登录 | `deviceId`、`metric`；可选 `limit`，最大 500，返回最近数据并按时间升序排列 |
| GET | `/sprinkler-tasks` | 登录 | `siteId`；可选 `zoneId`、`status`、`from`、`to`、`page`、`pageSize` |
| POST | `/sprinkler-tasks` | ADMIN、SUPERVISOR、DEVICE_MANAGER | 创建请求 |
| POST | `/sprinkler-tasks/{id}/dispatch` | 同上 | 无 |
| POST | `/sprinkler-tasks/{id}/ack` | 同上 | 回执请求 |

创建请求：

```json
{
  "siteId": 1,
  "zoneId": 2,
  "reason": "现场道路扬尘，需要人工喷淋降尘",
  "plannedAt": null
}
```

回执请求：

```json
{ "success": false, "failureReason": "设备网关无响应" }
```

状态流：`CREATED → DISPATCHED → EXECUTED | FAILED`。`success=false` 时必须提供不超过 500 字的 `failureReason`，否则返回 `FAILURE_REASON_REQUIRED`。页面上的成功/失败回执均是业务闭环演示，不代表真实 PLC 或网关响应。

- 创建时锁定目标区域并校验区域归属、喷淋设备存在、启用且在线；同工地同区域计划时间的最小间隔由 `SPRINKLER_MINIMUM_INTERVAL_SECONDS` 控制，冲突返回 `SPRINKLER_INTERVAL_CONFLICT`。
- 下发时再次校验区域仍绑定喷淋设备且至少一台启用、在线；绑定丢失返回 `SPRINKLER_BINDING_INVALID`，不可用返回 `SPRINKLER_UNAVAILABLE`。
- 重复下发已处于 `DISPATCHED` 的任务会返回原 `commandId`，不会重复写审计或事件；重复提交相同最终回执同样返回原结果，相反结果返回 `TASK_ACK_CONFLICT`。
- `DISPATCHED` 超过 `SPRINKLER_DISPATCH_TIMEOUT_SECONDS` 未收到回执时，调度器将其原子更新为 `FAILED` 并记录 `SPRINKLER_TASK_TIMEOUT` 系统审计。扫描周期和开关分别由 `SPRINKLER_TIMEOUT_SCAN_MS`、`SPRINKLER_TIMEOUT_SCAN_ENABLED` 控制。
- 任务列表的 `from`、`to` 按计划时间过滤并包含边界；开始时间晚于结束时间返回 `INVALID_TIME_RANGE`，非法状态返回 `INVALID_TASK_STATUS`。

## 12. 审计

| 方法 | 路径 | 权限 | 参数 |
| --- | --- | --- | --- |
| GET | `/audit-logs` | ADMIN | 必填 `siteId`；可选 `page`、`pageSize` |

审计项包含用户、动作、对象类型、对象 ID、说明、追踪编号和时间。设备维护、规则修改、风险复核、告警处置、喷淋任务和退出登录均会留痕。服务端按对象归属工地或操作者工地范围推导审计项作用域，只返回当前管理员所选且已授权工地的数据。

## 13. AI Agent 问答

AI Agent 接口均要求登录。下表使用包含 `/api` 的完整路径。会话同时绑定创建者和工地：列表只返回“当前用户 + 指定工地”的会话；读取或发送消息时也会复核会话创建者以及当前用户对会话工地的访问范围。因此，同一工地的其他用户也不能读取该会话，跨工地访问返回工地范围错误或按安全默认隐藏为会话不存在。

| 方法 | 路径 | 参数或请求体 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/agent/config` | 无 | 返回当前模式、模型标识、可用状态和单次问题字符上限；不返回 API Key、Base URL 或白名单 |
| GET | `/api/agent/provider-config` | 无 | 返回当前用户自己的 Base URL、模型、`apiKeyConfigured`、服务端凭据存储状态及管理员允许项；不返回 API Key 或密文 |
| PUT | `/api/agent/provider-config` | `{ "baseUrl": "https://api.openai.com/v1", "model": "gpt-4.1-mini", "apiKey": "..." }` | 保存当前用户配置；`apiKey` 省略、`null` 或空白时保留已有密钥 |
| DELETE | `/api/agent/provider-config` | 无 | 清除当前用户的服务商配置并回退到全局模式 |
| GET | `/api/agent/conversations` | 必填 `siteId`；可选 `page`、`pageSize` | 按更新时间倒序返回当前用户在该工地的会话 |
| POST | `/api/agent/conversations` | `{ "siteId": 1, "title": "可选标题" }` | 创建会话；标题省略或为空时使用“新对话”，最长 80 字符 |
| GET | `/api/agent/conversations/{id}/messages` | 可选 `page`、`pageSize`，默认 1/50 | 分页读取当前用户拥有的会话消息 |
| POST | `/api/agent/conversations/{id}/messages` | `{ "content": "汇总当前未闭环告警" }` | 发送问题并返回本轮用户消息与助手消息 |

配置示例：

```json
{
  "mode": "DEMO",
  "model": "demo-site-summary",
  "available": true,
  "maxContentChars": 8000
}
```

个人服务商配置查询示例：

```json
{
  "configured": true,
  "baseUrl": "https://api.openai.com/v1",
  "model": "gpt-4.1-mini",
  "apiKeyConfigured": true,
  "credentialStorageAvailable": true,
  "userConfigEnabled": true,
  "approvedBaseUrls": ["https://api.openai.com/v1"],
  "approvedModels": ["gpt-4.1-mini"],
  "customModelAllowed": false,
  "effectiveMode": "OPENAI_COMPATIBLE",
  "effectiveModel": "gpt-4.1-mini",
  "available": true
}
```

个人配置按登录用户隔离。保存成功后，该用户实际使用 `OPENAI_COMPATIBLE`，其他用户不受影响；全局 `DISABLED` 始终是总开关。`configured` 仅表示记录存在，界面应以 `available` 和 `effectiveMode` 判断当前是否真正可调用。Base URL 必须归一化后精确命中管理员白名单，远程地址只允许 HTTPS，HTTP 仅允许精确 loopback。模型标识须满足字符约束；管理员模型允许列表非空时还必须精确命中列表。API Key 是只写字段：首次保存必须填写，后续 PUT 省略或留空才表示保留；响应、审计和会话均不回显。服务端使用 AES-256-GCM 加密后持久化，且没有部署环境提供的主密钥时 PUT 会失败关闭。DELETE 不需要解密密钥。

`mode` 只可能是：

- `DEMO`：使用当前工地数据库聚合快照生成带“演示模式”标识的确定性回答，不调用外部模型；
- `OPENAI_COMPATIBLE`：由后端调用当前用户配置或管理员全局配置的 OpenAI 兼容 `/chat/completions` 服务；
- `DISABLED`：问答停用，配置仍可读取，发送问题返回 `503 AI_AGENT_DISABLED`。

“工地聚合快照”的字段范围固定为：工地名称、区域数、设备总数与启用设备在线/离线数、活动告警数及其中高等级数、待复核 AI 风险数、待执行/执行中喷淋任务数和最近遥测时间。外部模式还可追加与当前问题匹配的最多 3 个当前工地已发布知识摘录；草稿、待审、驳回和归档文档不会进入上下文。它不包含任意原始表访问；具体实时指标仍应回到监测页面查询。

会话分页项包含 `id`、`siteId`、`title`、`createdAt`、`updatedAt`、`messageCount` 和 `lastMessagePreview`。消息项包含 `id`、`conversationId`、`role`、`content`、`mode`、`model` 和 `createdAt`。消息查询先按最新记录取分页窗口，再把窗口内记录恢复为时间正序；因此第 1 页表示“最新一窗”，不是最早一页。

发送成功数据结构：

```json
{
  "conversation": { "id": 1, "siteId": 1, "title": "汇总当前未闭环告警" },
  "userMessage": { "role": "USER", "content": "汇总当前未闭环告警" },
  "assistantMessage": {
    "role": "ASSISTANT",
    "content": "【演示模式】……",
    "mode": "DEMO",
    "model": "demo-site-summary"
  }
}
```

首次成功问答会用问题前 32 个 Unicode 字符更新默认标题。单个后端实例先在有界等待时间内取得会话 ID 对应的公平条带锁，以串行化同一会话的发送；锁等待超时或线程被中断返回 `503 AI_AGENT_BUSY`。回答成功后才在同一事务中写入用户消息、助手消息、会话更新时间与审计记录，因此外部服务调用失败时不会留下半轮消息。请求体必须是 JSON 对象且不能包含未声明字段；问题不能为空，并受 `maxContentChars` 限制。

主要错误状态与错误码：

| HTTP | 错误码 | 场景 |
| --- | --- | --- |
| 400 | `INVALID_AGENT_REQUEST`、`INVALID_AGENT_TITLE`、`INVALID_AGENT_CONTENT`、`INVALID_AGENT_PROVIDER_MODEL`、`INVALID_AGENT_PROVIDER_API_KEY`、`AGENT_PROVIDER_BASE_URL_NOT_APPROVED`、`AGENT_PROVIDER_MODEL_NOT_APPROVED` | 请求体、标题、问题内容或个人服务商配置不合法 |
| 403 | `SITE_SCOPE_DENIED` | 无权访问指定工地 |
| 404 | `SITE_NOT_FOUND`、`AI_CONVERSATION_NOT_FOUND` | 工地或当前用户拥有的会话不存在 |
| 429 | `AI_AGENT_RATE_LIMITED` | 当前用户在单个后端实例内的外部请求已达到每分钟上限 |
| 502 | `AI_AGENT_PROVIDER_ERROR` | 外部兼容服务拒绝请求、返回非法响应或调用失败 |
| 503 | `AI_AGENT_DISABLED`、`AI_AGENT_UNAVAILABLE`、`AI_AGENT_BUSY`、`AI_AGENT_USER_CONFIG_DISABLED`、`AI_AGENT_CREDENTIAL_STORAGE_UNAVAILABLE`、`AI_AGENT_CREDENTIAL_UNAVAILABLE` | 功能停用、外部配置/凭据不可用、会话锁等待失败，或外部请求并发舱壁在等待时间内无可用许可 |
| 504 | `AI_AGENT_PROVIDER_TIMEOUT` | 外部兼容服务响应超时 |

`OPENAI_COMPATIBLE` 模式当前为非流式完整响应。启用后，服务端会把当前问题、受上限约束的本会话历史消息和上述字段范围内的工地摘要发送给配置的服务商；浏览器只在保存请求中提交新 API Key，服务端响应永不回显，前端也不得持久化该输入。外部调用受单实例并发舱壁和每用户固定分钟窗口限额保护；远程 Base URL 只允许 HTTPS，HTTP 只允许精确 loopback 主机且仍须命中白名单。服务端不跟随重定向，并会在 JSON 反序列化前有界读取响应；超限响应按 `502 AI_AGENT_PROVIDER_ERROR` 返回。外部模式的配置与数据边界见[部署文档](DEPLOYMENT.md)和[已知限制](KNOWN_LIMITATIONS.md)。

## 14. WebSocket 实时事件

连接地址：

```text
ws://127.0.0.1:8080/ws/events?token=<URL 编码后的登录 Token>
```

握手成功首先收到：

```json
{ "type": "connection.ready", "occurredAt": "2026-07-20T02:30:00Z" }
```

握手时服务端校验 Token；无效 Token 会以 WebSocket 关闭码 `1008` 断开。连接建立后，每次投递仍会复核该 Token，退出登录、刷新令牌或管理员撤销会话后，旧连接不会继续接收事件。

其他消息结构：

```json
{
  "type": "alarm.status.changed",
  "occurredAt": "2026-07-20T02:31:00Z",
  "payload": { "alarmId": 1, "siteId": 1, "fromStatus": "PENDING", "toStatus": "PROCESSING" }
}
```

事件类型包括 `telemetry.updated`、`alarm.created`、`alarm.updated`、`alarm.status.changed`、`risk.created`、`risk.reviewed`、`device.updated`、`device.status.changed`、`sprinkler.task.changed`。业务事件必须在 `payload.siteId` 中携带所属工地；服务端只向登录用户 `siteIds` 与该工地相交的连接投递。缺失、非法或非正数 `siteId` 的业务事件按安全默认直接丢弃，不会退化为全局广播。消息外层结构和事件类型保持不变。

该通道为尽力投递，断线后应通过 REST 重新加载事实数据。

## 15. TCP 遥测协议

每个方向均采用：

1. 4 字节无符号语义的大端帧长（实现读取为正 `int32`）；
2. 指定长度的 UTF-8 JSON；
3. 最大负载 1 MiB。

请求 JSON 与 `POST /telemetry` 相同。ACK 示例：

```json
{
  "success": true,
  "messageId": "SIM-20260720103000-000001",
  "insertedMetrics": 7,
  "duplicateMetrics": 0,
  "receivedAt": "2026-07-20T02:30:00Z"
}
```

当前 TCP 接口没有 TLS 或设备认证，只适合受控本地网络。

## 16. 独立 AI 适配器

基址：`http://127.0.0.1:5001`，不属于 Spring Boot `/api`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health` | 返回模式、权重文件是否存在和验证提示 |
| POST | `/infer` | 接收 `{ "imageBase64": "..." }`；默认返回 503 |

单图 Base64 解码后最大 8 MiB，请求体最大 12 MiB。固定演示模式必须显式设置 `AI_DEMO_MODE=true`；真实推理必须设置 `AI_ENABLE_MODEL=true` 并安装依赖。

## 17. 知识、报告审核与推送

以下接口要求 `ADMIN` 或 `SUPERVISOR`；最终审核和知识归档只允许 `ADMIN`。

| 方法 | 路径 | 请求或参数 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/knowledge-documents` | `siteId`，可选 `keyword/status/page/pageSize` | 知识文档分页 |
| POST | `/api/knowledge-documents` | `siteId/title/category/sourceReference/content` | 创建 `DRAFT` |
| PUT | `/api/knowledge-documents/{id}` | 同上 | 只允许草稿或驳回状态，保存后回到 `DRAFT` |
| POST | `/api/knowledge-documents/{id}/submit` | 无 | `DRAFT → PENDING_REVIEW` |
| POST | `/api/knowledge-documents/{id}/review` | `{ "action": "APPROVE|REJECT", "note": "..." }` | 管理员发布或驳回；驳回意见必填 |
| POST | `/api/knowledge-documents/{id}/archive` | 无 | 管理员归档已发布知识 |
| GET/POST/PUT | `/api/report-templates[/{id}]` | 模板列表、创建或编辑 | 模板字段使用双花括号固定占位符 |
| PATCH | `/api/report-templates/{id}/enabled` | `{ "enabled": true }` | 启停模板 |
| GET | `/api/reports` | `siteId`，可选 `status/page/pageSize` | 报告分页 |
| POST | `/api/reports/generate` | `siteId/templateId/title?` | 从实时数据库摘要生成 `DRAFT` |
| PUT | `/api/reports/{id}` | `title/content` | 编辑草稿或驳回报告 |
| POST | `/api/reports/{id}/submit` | 无 | `DRAFT → PENDING_REVIEW` |
| POST | `/api/reports/{id}/review` | `APPROVE/REJECT + note?` | 管理员审核为 `APPROVED/REJECTED` |
| GET/POST | `/api/push-channels` | `siteId` 或渠道配置 | 渠道分页和创建；类型 `LOG/WEBHOOK` |
| PATCH | `/api/push-channels/{id}/enabled` | `{ "enabled": true }` | 启停渠道 |
| GET/POST | `/api/reports/{id}/deliveries` | 分页参数或 `{ "channelId": 1 }` | 查询/创建投递；报告必须 `APPROVED` |

Webhook 不会接受浏览器传入密钥。数据库只保存可选环境变量名称；发送时再从服务器进程环境读取 Bearer Token。服务端还会复核全局开关、精确地址白名单、HTTPS/loopback 策略和超时，不跟随重定向。

## 18. 外部集成与生产监控

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/integrations?siteId=1` | ADMIN/SUPERVISOR | 返回视频、视觉 AI、喷淋网关和监控的真实配置状态 |
| POST | `/api/integrations/{type}/check?siteId=1` | ADMIN/SUPERVISOR | 主动检查 `VIDEO/VISION_AI/SPRINKLER_GATEWAY/PRODUCTION_MONITORING` |
| PUT | `/api/cameras/{id}/stream` | ADMIN/SUPERVISOR/DEVICE_MANAGER | `{ "siteId": 1, "streamUrl": "..." }`；空地址用于清除 |
| POST | `/api/integrations/vision-ai/infer` | ADMIN/SUPERVISOR/DEVICE_MANAGER | `siteId/cameraId/imageBase64`；合法检测写入待复核风险 |
| POST | `/api/integration-callbacks/sprinkler` | 网关共享令牌 | `X-Gateway-Token` + `commandId/success/failureReason?` |
| GET | `/actuator/health` | 公开 | 最小健康状态 |
| GET | `/actuator/prometheus` | ADMIN | Prometheus 文本指标 |

集成状态含义和外部协议见[第四轮治理工作流与外部集成](ROUND4_INTEGRATIONS.md)。
