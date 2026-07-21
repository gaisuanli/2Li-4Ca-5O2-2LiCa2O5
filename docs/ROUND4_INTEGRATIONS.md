# 第四轮治理工作流与外部集成

本轮把知识库、报告、人工审核、推送、视频、视觉 AI、喷淋网关和生产监控做成了可配置、可探测、可审计的模块。默认配置不会把演示状态显示成生产接入成功；只有真实端点通过检查后，集成中心才显示 `READY`。

## 1. 知识与报告治理

- 知识文档状态：`DRAFT → PENDING_REVIEW → PUBLISHED`，管理员也可驳回为 `REJECTED` 或把已发布版本归档为 `ARCHIVED`。
- 只有 `PUBLISHED` 且属于当前授权工地的文档可以进入 AI Agent 上下文；单次最多选择 3 个匹配摘录，每篇最多 1000 字符。
- 报告由受控模板和当前数据库摘要生成，先保存为 `DRAFT`，提交后进入 `PENDING_REVIEW`；只有管理员审核为 `APPROVED` 后才能投递。
- 模板只支持显式字段：`siteName`、`generatedAt`、`deviceTotal`、`onlineDeviceTotal`、`activeAlarmTotal`、`highAlarmTotal`、`pendingRiskTotal`、`pendingSprinklerTotal`。未知字段会被拒绝。
- `LOG` 渠道把已批准报告写入投递和审计记录；`WEBHOOK` 渠道只有在服务端启用且地址精确命中白名单时才发起网络请求。密钥只从渠道指定的环境变量读取，不存入数据库或返回浏览器。

## 2. 视频接入与探测

在“集成中心”选择摄像头并配置 `HTTP/HTTPS/RTSP/RTMP` 地址。主机必须存在于：

```text
VIDEO_ALLOWED_HOSTS=video-gateway.example.com,10.20.1.15
```

HTTP/HTTPS 使用有超时、不跟随重定向的 `HEAD` 探测；RTSP/RTMP 只探测媒体端口。`READY` 表示传输端点可达，最终解码、令牌、跨域和浏览器兼容仍须在“视频监控”页面验收。

## 3. 视觉 AI

独立适配器保持默认关闭。完成模型、标签、验证集和运行环境验收后，配置：

```text
VISION_AI_ENABLED=true
VISION_AI_BASE_URL=https://vision.internal.example/v1
VISION_AI_ALLOWED_BASE_URLS=https://vision.internal.example/v1
VISION_AI_API_KEY=<server-side-key>
```

本机联调可继续使用默认 `http://127.0.0.1:5001`。业务后端先检查精确白名单，再调用 `/health` 和 `/infer`；推理结果经过字段与置信度校验后写入 `ai_risk`，状态固定为 `PENDING_REVIEW`，不会直接创建告警或控制设备。

## 4. 喷淋网关

模式包括 `DEMO`、`HTTP`、`DISABLED`。生产配置示例：

```text
SPRINKLER_GATEWAY_MODE=HTTP
SPRINKLER_GATEWAY_BASE_URL=https://iot.internal.example
SPRINKLER_GATEWAY_ALLOWED_BASE_URLS=https://iot.internal.example
SPRINKLER_GATEWAY_API_KEY=<server-side-key>
SPRINKLER_GATEWAY_CALLBACK_TOKEN=<at-least-16-random-characters>
```

平台下发：

```http
POST /commands/sprinkler
Authorization: Bearer <SPRINKLER_GATEWAY_API_KEY>
Content-Type: application/json
```

```json
{
  "platformCommandId": "CMD-12345678",
  "taskId": 1,
  "siteId": 1,
  "zoneId": 6,
  "action": "START",
  "reason": "道路扬尘",
  "requestedAt": "2026-07-20T08:00:00Z"
}
```

网关应返回 2xx 和 `{ "accepted": true, "commandId": "可选外部编号" }`，并用平台命令编号回调：

```http
POST /api/integration-callbacks/sprinkler
X-Gateway-Token: <SPRINKLER_GATEWAY_CALLBACK_TOKEN>
Content-Type: application/json
```

```json
{ "commandId": "CMD-12345678", "success": true }
```

失败回执必须带 `failureReason`。回调令牌以常量时间比较，状态更新仍使用条件更新和最终态冲突保护。现场急停、天气联锁、设备签名和 PLC 侧幂等仍需由实际网关实现并独立验收。

## 5. 生产监控

- 无认证健康检查：`GET /actuator/health`。
- Prometheus 指标：`GET /actuator/prometheus`，仅 `ADMIN` Bearer Token 可访问。
- 集成中心的“生产监控”检查会同时验证数据库和指标注册表，并返回当前注册指标数量。

生产环境应在反向代理或服务网格中进一步限制 Prometheus 来源，并接入长期指标存储、告警规则、日志轮转和集中追踪。

## 6. 验收结论的解释

- `NOT_CONFIGURED`：未提供或未启用真实配置。
- `SIMULATED`：仅演示适配器工作，不代表现场设备动作。
- `CONFIGURED`：配置项齐全，但本次没有主动网络探测结果。
- `READY`：本次健康或传输探测成功。
- `DEGRADED/DOWN/MISCONFIGURED`：部分失败、不可达或未通过白名单/协议校验。

没有真实地址、账号、视频流、网关或硬件时，项目只能完成适配器与验收入口，不能据此宣称第三方系统已经上线。
