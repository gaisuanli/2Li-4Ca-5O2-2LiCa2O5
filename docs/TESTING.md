# 测试与验收

## 1. 验收范围

验收以 Windows PC 和桌面浏览器为目标，覆盖构建、模块单元测试、后端启动、H2/MySQL 初始化、角色权限、分页、核心业务状态机、TCP 上报、实时事件和页面视觉检查。移动端不在本次验收范围内。

推荐验收环境：

- Windows 10/11；
- Chrome 或 Edge 当前稳定版；
- `1440 × 900` 或更高分辨率，浏览器缩放 100%；
- JDK 17、Maven 3.9+、Node.js 20.19+（或 22.12+）、pnpm 11；
- Python 3.11+ 用于完整构建中的 AI 安全模式测试；MySQL 8.0 仅在 MySQL 验收时需要。

## 2. 自动化测试矩阵

| 模块 | 命令 | 当前覆盖 |
| --- | --- | --- |
| PC 管理端 | `pnpm --dir frontend test` | API/会话、分页、五类规则指标、治理状态机、集成状态、塔吊真实/清洗节点三通道、喷淋和 Agent 辅助逻辑，共 39 项测试 |
| PC 管理端构建 | `pnpm --dir frontend build` | Vue/Vite 生产构建、资源引用和语法 |
| PC 浏览器 E2E | `pnpm --dir frontend test:e2e` | Playwright Chromium，固定 `1440 × 900`；登录守卫、设备服务端分页、塔吊 WebGL 三通道、告警筛选和 Agent DEMO，共 5 条；console/pageerror/意外 5xx 为失败 |
| 设备模拟器 | `pnpm --dir device-simulator test` | 帧往返、半帧缓冲、塔吊 CSV 映射、多设备档案和未知设备拒绝，共 5 项测试 |
| AI 适配器 | 在 `ai-service` 执行 `python -m unittest discover -s tests -p "test_*.py"` | 默认禁用、健康检查不夸大模型能力，共 2 项测试 |
| 后端 | `mvn -f backend/pom.xml package` | 22 个默认测试套件、79 项测试：除原有主链路外覆盖五类设备自动告警、知识/报告审核、受控投递、外部适配器网络契约、个人 Agent 凭据加密/隔离和 Actuator 权限；另有 MySQL 8 专项 2 项 |
| 生产依赖审计 | `pnpm audit --prod` | ECharts 升级至 6.1.0 后无已知漏洞 |

统一执行：

```powershell
Set-Location D:\building-agent\platform
.\scripts\build.ps1
```

全新环境安装依赖后执行：

```powershell
.\scripts\build.ps1 -InstallDependencies
```

构建成功应生成：

- `frontend/dist/index.html`；
- `backend/target/building-safety-api-1.0.0.jar`。

`build.ps1 -SkipTests` 只用于定位构建问题，不作为正式验收结果。

## 3. 启动与基础健康检查

```powershell
Copy-Item .env.example .env -ErrorAction SilentlyContinue
.\scripts\start.ps1 -WithSimulator -WithAi
```

脚本会依次检查后端、前端、模拟器和 AI 健康地址。也可人工核对：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/health
Invoke-RestMethod http://127.0.0.1:5001/health
```

通过标准：后端 `data.status` 与 `data.database` 均为 `UP`；AI 返回 `status=UP`，默认 `mode=DISABLED`。

## 4. REST 冒烟测试

### 4.1 登录与认证

```powershell
$loginBody = @{ username = 'supervisor'; password = 'Safe@123' } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/auth/login -ContentType 'application/json' -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.data.token)" }
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/auth/me -Headers $headers
```

检查项：

- 正确账号返回随机 Token、角色、工地范围和过期时间；
- `POST /auth/refresh` 返回可用的新 Token，旧 Token 立即返回 `401`；
- 错误口令返回 `401 BAD_CREDENTIALS`；
- 缺少 Token 访问业务接口返回 `401 UNAUTHORIZED`；
- 响应头和 JSON 均含相同的追踪编号。

### 4.2 分页

```powershell
$page = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/devices?siteId=1&page=2&pageSize=10' -Headers $headers
$page.data | Select-Object total,page,pageSize
```

检查项：

- 演示数据共有 12 台设备，第 1 页 10 条、第 2 页 2 条；
- 返回 `items/total/page/pageSize`；
- `pageSize=1000` 时实际返回的 `pageSize` 为 100；
- 设备关键字、类型、连接状态和区域筛选后总数正确；
- 设备、AI 风险、告警、规则、摄像头、喷淋任务、遥测历史、用户、审计、Agent 会话和 Agent 消息分页遵循同一约定；
- Agent 会话按更新时间倒序；消息第 1 页取得最新窗口，但每个窗口内按时间正序展示；
- PC 端选择每页 20 或 50 条时回到第 1 页，首页/上一页/下一页/末页禁用状态正确。

### 4.3 角色权限

分别使用三个演示账号验证：

| 场景 | 预期 |
| --- | --- |
| DEVICE_MANAGER 调用告警处置 | `403 FORBIDDEN` |
| DEVICE_MANAGER 调用规则写接口 | `403 FORBIDDEN` |
| SUPERVISOR 新增设备 | `403 FORBIDDEN` |
| SUPERVISOR 读取审计日志 | `403 FORBIDDEN` |
| SUPERVISOR 读取或修改用户 | `403 FORBIDDEN` |
| ADMIN 执行上述操作 | 成功 |

前端还应隐藏无权限导航或操作按钮，但不能以按钮隐藏替代服务端鉴权测试。

### 4.4 管理员用户管理

1. 使用 ADMIN 打开“用户管理”，按用户名或姓名查询并切换 10/20/50 条分页。
2. 新建一个 SUPERVISOR，用户名满足 3–50 位规则，密码至少 8 位，工地范围选择存在的工地。
3. 用新账号登录，确认角色菜单和工地范围正确。
4. ADMIN 重置其密码，确认旧 Token 立即失效，旧密码不能登录，新密码可以登录。
5. ADMIN 停用该用户，确认现有会话失效且不能再次登录；重新启用后可登录。
6. 尝试停用当前 ADMIN 自身，预期 `409 CANNOT_DISABLE_SELF`。
7. 检查 `USER_CREATE`、`USER_PASSWORD_RESET`、`USER_ENABLE_CHANGE` 审计记录。

## 5. 核心业务验收

### 5.1 遥测幂等和规则判断

1. 以 ADMIN 或 DEVICE_MANAGER 登录。
2. 两次提交相同 `messageId`、相同指标的 `/telemetry` 请求。
3. 第一次 `insertedMetrics > 0`；第二次对应指标计入 `duplicateMetrics`。
4. 提交 `TC-001` 的 `windSpeed=11.8`，确认命中风速规则。
5. 抑制窗口内再次使用新消息编号上报，确认原告警 `occurrences` 增加，而非生成同规则重复告警。

### 5.2 告警状态机

对一条 `PENDING` 告警依次执行：

```text
CONFIRM → RESOLVE → CLOSE
```

每一步应满足：状态正确前进、详情时间线新增一条动作、审计日志新增记录、WebSocket 收到 `alarm.status.changed`。尝试越级关闭应返回 `409 INVALID_ALARM_TRANSITION`。

查询与导出还应验证：

1. 使用 `zoneId`、`from`、`to` 联合筛选，确认时间边界包含且列表总数正确；非法时间范围和跨工地区域应返回明确错误码。
2. 组合状态、等级、来源和关键字后点击“导出 CSV”，确认响应文件名为 `alarms-site-<siteId>.csv`、内容带 UTF-8 BOM，且记录集合与相同筛选的列表一致。
3. `source=SYSTEM` 应包含 `SYSTEM_DEVICE_OFFLINE`，精确来源筛选也应生效。

### 5.3 AI 人工复核

1. 可先用 ADMIN 或 DEVICE_MANAGER 调用 `POST /api/risks`，以存在的 `cameraCode` 写入一条 `PENDING_REVIEW` 风险；摄像头、置信度和工地范围校验应生效。
2. 用 supervisor 打开“AI 风险”，筛选“待复核”。
3. 选择演示风险并填写现场核查说明。
4. 点击“确认有效”。
5. 风险变为 `CONFIRMED`，响应携带 `alarmId`，告警中心出现来源为 `AI_RISK` 的高等级告警。
6. 对已复核记录再次提交应返回 `409 RISK_ALREADY_REVIEWED`。

### 5.4 AI Agent 问答

1. 调用 `GET /api/agent/config`，默认应返回 `mode=DEMO`、`available=true`、模型标识和 `maxContentChars`，且响应中不得出现 API Key、Base URL 或允许列表。
2. 使用 supervisor 创建 `{ "siteId": 1 }` 会话，确认标题默认为“新对话”；发送“汇总当前工地尚未闭环的告警”，确认返回 `conversation/userMessage/assistantMessage`，助手消息为 `mode=DEMO` 并明确标识演示模式。
3. 确认首次问答更新默认标题；会话列表和消息列表分别验证 `items/total/page/pageSize`，并验证消息最新窗口、窗口内时间正序的语义。
4. 用同工地的另一个账号读取或发送该会话，预期 `404 AI_CONVERSATION_NOT_FOUND`；使用无权访问该工地的账号创建或列出会话，预期 `403 SITE_SCOPE_DENIED`。
5. 提交空问题、超长问题、超长标题、非对象请求体或额外字段，分别确认稳定的 `INVALID_AGENT_CONTENT`、`INVALID_AGENT_TITLE` 或 `INVALID_AGENT_REQUEST`。
6. 设置 `AI_AGENT_MODE=DISABLED` 后发送问题，预期 `503 AI_AGENT_DISABLED`，消息总数不增加；将外部模式配置为缺少 Key、模型或白名单不匹配，预期 `503 AI_AGENT_UNAVAILABLE`，且配置接口仍不泄露秘密。
7. 地址安全测试应确认远程 HTTP、带用户信息/查询/片段的 URL 和白名单外地址均不可用；`http://localhost`、`http://127.0.0.1`、`http://[::1]` 仅在自身精确命中白名单时可用于本机桩服务，重定向不得被跟随。
8. 用本机兼容桩服务返回超长 `Content-Length`、无长度但超限的分块正文、非 2xx 正文和非法 JSON，确认服务端有界读取且统一返回 `502 AI_AGENT_PROVIDER_ERROR`，不会保存半轮消息或在日志/响应中泄露服务商正文。
9. 在隔离测试配置中降低 `AI_AGENT_PER_USER_REQUESTS_PER_MINUTE`，确认同一用户超过固定分钟配额返回 `429 AI_AGENT_RATE_LIMITED`；降低 `AI_AGENT_MAX_CONCURRENT_REQUESTS` 并缩短 `AI_AGENT_BULKHEAD_WAIT_MS`，用延迟桩服务并发请求，确认无许可时返回 `503 AI_AGENT_BUSY` 且许可最终归还。切回 `DEMO` 后重复问答，确认本地回答绕过仅用于外部调用的限流与舱壁，不误报上述错误。
10. 并发向同一会话发送两个问题，确认单实例条带锁保持请求顺序，后一请求能看到前一轮已保存的历史，不出现用户/助手消息交叉落库；将 `AI_AGENT_CONVERSATION_LOCK_WAIT_MS` 降低后占住对应条带，确认等待超时返回 `503 AI_AGENT_BUSY`；不同会话只按条带哈希共享有限锁集合。
11. 检查会话创建和消息发送审计按会话工地归属；选择其他工地时不得混入记录。

自动化只验证 DEMO 行为、本机兼容桩服务、配置保护、用户/工地隔离、地址/响应边界、单实例并发保护和错误事务边界。真实 `OPENAI_COMPATIBLE` 服务的模型效果、数据合规、性能与可用性必须在获得服务商凭据和审批后另行验收，当前基线不声称已经通过。

### 5.5 喷淋状态机

1. 选择关联在线喷淋设备的环境站。
2. 创建任务，确认状态为 `CREATED`。
3. 下发后为 `DISPATCHED` 且生成 `commandId`。
4. PC 页面分别记录一次“演示成功回执”和填写原因的“演示失败回执”，确认状态为 `EXECUTED`、`FAILED`；失败回执不填写原因应返回 `400 FAILURE_REASON_REQUIRED`。
5. 对同一任务重复下发，确认返回同一个 `commandId` 且只留一条下发审计；重复相同结果回执应幂等，相反结果返回 `409 TASK_ACK_CONFLICT`。
6. 创建后将关联喷淋设备停用、离线或移出区域，再下发应分别得到 `SPRINKLER_UNAVAILABLE` 或 `SPRINKLER_BINDING_INVALID`。
7. 同一区域在最短间隔内再次创建任务应返回 `409 SPRINKLER_INTERVAL_CONFLICT`。
8. 将测试任务的 `started_at` 调整到超时阈值之前并执行专项服务测试，确认只转为一次 `FAILED`，失败原因包含“超时”，并且仅有一条 `SPRINKLER_TASK_TIMEOUT` 审计。

喷淋专项自动化命令：

```powershell
cd backend
mvn -Dtest=edu.gzhu.sitesafe.SprinklerWorkflowIntegrationTest test
```

该测试包含 4 个用例，覆盖设备绑定与可用性、区域最短间隔、下发/回执幂等、失败原因校验和超时失败。

### 5.6 WebSocket 工地隔离

运行实时中心专项测试：

```powershell
cd backend
mvn -Dtest=edu.gzhu.sitesafe.realtime.RealtimeHubTest test
```

测试应验证：同工地连接可收到事件；其他工地连接收不到该事件；缺失或非法 `payload.siteId` 的业务事件不会广播；无效 Token 以关闭码 `1008` 拒绝；已注销或轮换失效的 Token 在下一次投递前关闭。事件消息仍使用 `type`、`occurredAt`、`payload` 三段结构。

### 5.7 管理员用户与审计工地隔离

1. 准备两个工地及分别只授权其中一个工地的 ADMIN 账号。
2. 调用 `GET /api/users`，确认只返回目标 `siteScope` 完全位于当前管理员范围内的账号。
3. 尝试创建、停用或重置其他工地账号，均应返回 `403 SITE_SCOPE_DENIED`；异常的历史 `siteScope` 应默认隐藏且不可修改。
4. 调用 `GET /api/audit-logs` 时省略 `siteId` 应失败；选择已授权工地只返回该工地记录，选择未授权工地返回 `403 SITE_SCOPE_DENIED`。

## 6. TCP 模拟器验收

1. 打开 <http://127.0.0.1:9200>，确认页面列出五台轮询设备。
2. 确认目标显示 `127.0.0.1:9100`，点击连接。
3. 点击单次发送，确认发送数与 ACK 数同步增加。
4. 开启自动发送，至少观察五个 5 秒周期后关闭，确认 `TC-001`、`TC-002`、`EL-001`、`FM-001` 和 `PIT-001` 均收到 ACK。
5. 在塔吊分析页确认塔吊最新指标或趋势变化，并检查回转角、吊钩高度和工作幅度三个模型绑定通道。
6. 在大型设备页确认升降机、高支模和深基坑最新指标变化。
7. 在告警中心确认超过六条大型设备默认规则阈值时产生或合并告警。

协议专项检查：拆分帧、粘连帧、非法长度、未知设备、停用设备、重复消息和越界指标。

## 7. PC 浏览器 E2E

Windows 默认复用系统 Microsoft Edge；Linux/CI 首次安装 Chromium：

```powershell
pnpm --dir frontend e2e:install
```

先生成最新后端可执行 JAR，再执行：

```powershell
mvn -q -f backend/pom.xml -DskipTests package
pnpm --dir frontend test:e2e
```

Playwright 默认自行启动内存 H2 后端和 Vite，使用单 worker 串行执行，不依赖现有业务数据库。失败重试会保存 trace，最终失败会保存截图；报告用 `pnpm --dir frontend test:e2e:report` 查看。需要复用已启动服务时显式设置 `E2E_REUSE_SERVICES=true` 和对应端口，避免误连其他环境。

## 8. PC 页面视觉验收

逐页检查：

- 左侧十四个模块导航、顶部工地与实时连接状态保持一致；
- 设备页的场景、详情、筛选表格和分页在 `1366 × 768` 下可操作；
- 大型设备页可切换升降机、高支模、深基坑；离线、空数据、加载失败状态清晰，趋势最多展示最近 48 点；
- 表格横向内容不相互覆盖，分页栏完整可见；
- 塔吊三维模型有加载中、失败或 WebGL 不可用的降级状态；模型语义节点缺失时只停用对应绑定，不影响其余模型浏览；
- 图表有可读标题、单位、时间轴和空数据状态；
- 视频未配置时明确显示状态，不出现伪造播放画面；
- AI Agent 具有独立会话栏与消息区，模式、加载、空、错误和发送等待状态清晰；长消息不会遮挡输入区，切换会话/工地后数据正确重载；
- 所有页面均具备加载、空、错误状态；
- 只有 ADMIN 可见用户管理和审计导航；device 看不到规则、用户管理和审计导航；
- 浏览器控制台无未处理异常，网络请求无意外 4xx/5xx。

不要求验证汉堡菜单、窄屏重排或触控手势。

## 9. MySQL 验收

1. 本机已安装 MySQL 8 Server 二进制时，优先执行 `./scripts/test-mysql-smoke-local.ps1`；它使用独立数据目录和端口，不读取已有 root 密码，也不修改现有 MySQL 服务。
2. 验收远程隔离服务时，通过进程环境注入管理员 JDBC URL、用户名和密码，不把 root/管理员密码写入仓库或 Maven 命令行，然后执行 `./scripts/test-mysql-smoke.ps1`。
3. 两种入口都会随机创建空库及最小权限应用账号，以 `mysql` profile 初始化，并在结束时自动删除临时资源。
4. 测试从当前 `schema-mysql.sql` 动态提取全部 `CREATE TABLE`，检查实际表集合、至少 20 表的防退化基线、InnoDB 引擎和 DDL 二次执行幂等性，因此 schema 后续扩表不需要同步硬编码最终数量。
5. 自动重跑登录、设备新增、遥测幂等、自动告警、告警状态机、AI 风险复核、Agent DEMO、知识发布、报告审核与投递、喷淋下发/回执和审计链路。
6. 共享或生产配置仍必须保持 `DEMO_DATA_ENABLED=false`；测试只在每次创建且随后销毁的隔离库中临时启用确定性演示数据。

完整凭据注入方式、权限边界、清理与故障处理见 [MySQL 8 空库初始化与业务冒烟](MYSQL_SMOKE.md)。

## 10. 退出与证据保留

```powershell
.\scripts\stop.ps1
```

交付验收建议保留：

- `scripts/build.ps1` 完整控制台输出；
- `runtime/logs/` 中各组件日志；
- 主要分页页面（设备、塔吊设备与历史、大型设备、喷淋任务、视频、AI 风险、AI Agent 会话与消息、告警、规则、用户、审计）的 PC 截图；
- 一次遥测 ACK、告警处置时间线和对应审计记录；
- H2 与 MySQL 各一次健康检查结果。

当前自动化缺口和不应作为生产结论的能力见[已知限制](KNOWN_LIMITATIONS.md)。
