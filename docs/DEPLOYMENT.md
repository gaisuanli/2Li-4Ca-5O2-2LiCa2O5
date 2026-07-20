# 构建与部署

## 1. 部署定位

交付脚本面向 Windows PC 的本地开发、课堂验收和单机演示。默认启动 Vite 开发服务器与 Spring Boot 可执行 JAR，不等同于互联网生产部署。正式部署应将 `frontend/dist/` 放入静态 Web 服务器，并为 REST/WebSocket 配置 HTTPS 反向代理。

## 2. 软件要求

| 软件 | 建议版本 | 用途 |
| --- | --- | --- |
| PowerShell | 5.1 或 7 | 交付脚本 |
| JDK | 17 | 后端编译与运行 |
| Maven | 3.9+ | 后端构建 |
| Node.js | 20.19+ 或 22.12+ | 前端与设备模拟器；与 Vite 7 的运行要求一致 |
| pnpm | 11 | 前端依赖与构建 |
| Python | 3.11+ | 完整构建中的 AI 安全模式测试，以及可选 AI 适配器；跳过测试且不启动 AI 时可省略 |
| MySQL | 8.0+ | 可选持久数据库 |

所有命令均从 `platform` 根目录执行。

若系统执行策略阻止本地 `.ps1`，审阅 `scripts/` 后可在当前 PowerShell 会话执行 `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass`；该设置只在当前进程有效，不修改计算机或用户级策略。

## 3. 环境文件

`.env.example` 是默认 H2 模板，`.env.mysql.example` 是 MySQL 模板。复制模板，不要直接修改模板：

```powershell
Copy-Item .env.example .env
```

启动脚本使用严格的 `KEY=VALUE` 解析器，不执行文件内容，只接受白名单变量；空行和以 `#` 开头的行会忽略。`.env` 已被 `.gitignore` 排除，不要提交口令。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | 8080 | Spring Boot HTTP 端口 |
| `TCP_ENABLED` | true | 是否启用后端 TCP 接入 |
| `TCP_PORT` | 9100 | 后端 TCP 端口，同时是模拟器默认目标 |
| `SPRINKLER_MINIMUM_INTERVAL_SECONDS` | 300 | 同工地同区域喷淋任务最短计划间隔 |
| `SPRINKLER_DISPATCH_TIMEOUT_SECONDS` | 120 | 已下发任务等待演示设备回执的超时秒数 |
| `SPRINKLER_TIMEOUT_SCAN_MS` | 30000 | 喷淋回执超时扫描周期（毫秒） |
| `SPRINKLER_TIMEOUT_SCAN_ENABLED` | true | 是否启用喷淋回执超时扫描 |
| `TOKEN_TTL_MINUTES` | 480 | 内存 Token 有效期 |
| `DEMO_DATA_ENABLED` | H2 为 true、MySQL 为 false | 是否启用基础演示数据，以及大型设备和扩展塔吊遥测的幂等补齐；共享或生产环境必须保持 false |
| `DEVICE_OFFLINE_MONITOR_ENABLED` | H2 为 false、MySQL 为 true | 是否启用设备心跳超时扫描；固定 H2 演示快照应保持关闭 |
| `DEVICE_OFFLINE_TIMEOUT_SECONDS` | 300 | 设备最近上报超过多少秒后自动离线，建议为正常上报周期的 3～5 倍 |
| `DEVICE_OFFLINE_SCAN_INTERVAL_MS` | 30000 | 心跳超时扫描固定延迟（毫秒） |
| `DEVICE_OFFLINE_INITIAL_DELAY_MS` | 60000 | 应用启动后的首次扫描延迟（毫秒） |
| `DEVICE_OFFLINE_BATCH_SIZE` | 200 | 单轮最多处理的超时候选设备 |
| `CORS_ORIGINS` | 本地 5173/4173 | 允许的 PC 前端来源，逗号分隔 |
| `SPRING_PROFILES_ACTIVE` | 空 | 设置 `mysql` 切换 MySQL profile |
| `DB_URL` | H2 文件 URL | JDBC 地址 |
| `DB_USERNAME` | `sa` | 数据库账号 |
| `DB_PASSWORD` | 空 | 数据库密码 |
| `DB_DRIVER` | `org.h2.Driver` | 默认数据库驱动；MySQL profile 会覆盖 |
| `FRONTEND_PORT` | 5173 | 本地 PC 管理端端口，由启动脚本传给 Vite |
| `VITE_API_BASE` | `/api` | PC 端 API 基址；默认通过 Vite 代理到后端 8080 |
| `VITE_WS_BASE` | 空 | 空值时通过当前前端地址的 `/ws` 代理；也可填写完整 `ws://`/`wss://` 地址 |
| `AI_AGENT_MODE` | `DEMO` | 主业务内的 Agent 模式：`DEMO`、`OPENAI_COMPATIBLE` 或 `DISABLED`；默认不调用外部模型 |
| `AI_AGENT_MODEL` | `demo-site-summary` | Agent 返回和外部兼容请求使用的模型标识；外部模式必须填写服务商支持的真实标识 |
| `AI_AGENT_BASE_URL` | `https://api.openai.com/v1` | OpenAI 兼容服务基址；后端会在其后调用 `/chat/completions`，且必须与白名单中的一项精确匹配 |
| `AI_AGENT_API_KEY` | 空 | 外部兼容服务密钥；仅供 Spring Boot 服务端读取，禁止写入 `VITE_*`、前端源码、浏览器存储或版本库 |
| `AI_AGENT_ALLOWED_BASE_URLS` | `https://api.openai.com/v1` | 允许的兼容服务基址白名单，逗号分隔；`AI_AGENT_BASE_URL` 归一化后必须与其中一项精确相等 |
| `AI_AGENT_USER_CONFIG_ENABLED` | true | 是否允许登录用户保存个人兼容服务配置；`AI_AGENT_MODE=DISABLED` 仍可全局停用问答 |
| `AI_AGENT_CREDENTIAL_ENCRYPTION_KEY` | 空 | Base64 编码的随机 32 字节 AES 主密钥；只允许由部署环境或密钥管理系统注入，未配置或无效时个人配置保存失败关闭 |
| `AI_AGENT_APPROVED_MODELS` | 空 | 可选的用户模型标识精确允许列表，逗号分隔；留空允许符合格式约束的自定义模型标识 |
| `AI_AGENT_CONNECT_TIMEOUT_MS` | 5000 | Agent 外部请求连接超时；服务端有效范围 100～30000 毫秒 |
| `AI_AGENT_READ_TIMEOUT_MS` | 60000 | Agent 外部请求读取超时；服务端有效范围 500～180000 毫秒 |
| `AI_AGENT_MAX_CONTENT_CHARS` | 8000 | 单次用户问题字符上限；服务端有效范围 100～16000 |
| `AI_AGENT_MAX_RESPONSE_CHARS` | 16000 | 接受并保存的单次助手回答字符上限；服务端有效范围 100～32000 |
| `AI_AGENT_MAX_HISTORY_MESSAGES` | 20 | 发送给外部兼容服务的最近历史消息上限；服务端有效范围 0～100 |
| `AI_AGENT_CONVERSATION_LOCK_STRIPES` | 256 | 单实例内用于保持同一会话问答顺序的固定公平条带锁数量；有效范围 16～4096 |
| `AI_AGENT_CONVERSATION_LOCK_WAIT_MS` | 5000 | 等待会话条带锁的最长时间；有效范围 0～120000 毫秒，超时或等待线程被中断均返回 `503 AI_AGENT_BUSY` |
| `AI_AGENT_MAX_CONCURRENT_REQUESTS` | 8 | 单个后端实例允许同时执行的外部兼容服务请求数；有效范围 1～128，超过后先按等待时间尝试进入 |
| `AI_AGENT_PER_USER_REQUESTS_PER_MINUTE` | 20 | 单个后端实例内每位用户每个固定分钟窗口允许的外部兼容服务请求数；有效范围 1～1000 |
| `AI_AGENT_BULKHEAD_WAIT_MS` | 100 | 外部请求等待并发舱壁许可的最长时间；有效范围 0～5000 毫秒，超时返回 `503 AI_AGENT_BUSY` |
| `SIMULATOR_WEB_PORT` | 9200 | 模拟器控制台端口 |
| `TCP_HOST` | 127.0.0.1 | 模拟器连接的后端地址 |
| `AI_PORT` | 5001 | AI 适配器端口 |
| `AI_DEMO_MODE` | false | 是否返回明确标记的固定演示结果 |
| `AI_ENABLE_MODEL` | false | 是否尝试加载真实模型运行时 |

本地开发代理会从 `SERVER_PORT` 自动确定后端 HTTP 与 WebSocket 目标，因此修改后端端口时可继续使用同源的 `VITE_API_BASE=/api` 和空的 `VITE_WS_BASE`。若修改 `FRONTEND_PORT`，还要把对应 `http://localhost:<新端口>` 与 `http://127.0.0.1:<新端口>` 加入 `CORS_ORIGINS`。

Agent 默认 `DEMO` 模式只使用当前数据库的工地聚合快照，不连接外部服务，也不代表真实大模型已经验收。接入自有 API 时，至少设置：

```text
AI_AGENT_MODE=OPENAI_COMPATIBLE
AI_AGENT_MODEL=<服务商支持的模型标识>
AI_AGENT_BASE_URL=https://<受信服务商>/v1
AI_AGENT_API_KEY=<从密钥管理系统注入>
AI_AGENT_ALLOWED_BASE_URLS=https://<受信服务商>/v1
AI_AGENT_USER_CONFIG_ENABLED=true
AI_AGENT_CREDENTIAL_ENCRYPTION_KEY=<Base64 编码的随机 32 字节主密钥>
AI_AGENT_APPROVED_MODELS=<可选的模型精确允许列表>
```

`AI_AGENT_BASE_URL` 只有在去除末尾斜杠等归一化后与 `AI_AGENT_ALLOWED_BASE_URLS` 的一项精确相等时才可用；不能用域名后缀、子串或重定向绕过白名单，客户端也不会跟随重定向。远程地址只允许 HTTPS；HTTP 仅允许主机精确为 `localhost`、`127.0.0.1` 或 `::1` 的本机联调地址，而且仍须精确命中白名单。远程服务商应由组织审批其数据处理与保留规则。

启用个人配置时，`AI_AGENT_ALLOWED_BASE_URLS` 仍由管理员控制，用户不能扩展白名单。用户保存的 API Key 通过一次只写请求进入后端，以 AES-256-GCM 加密后写入 `ai_agent_provider_config`；每次加密使用随机 nonce，并以用户 ID 作为认证附加数据。GET、审计和会话都不包含明文或密文。`AI_AGENT_CREDENTIAL_ENCRYPTION_KEY` 禁止写入数据库、`.env.example`、前端变量或版本库；数据库备份也必须与该主密钥分离保管。当前只支持单主密钥，轮换前必须先规划对已有密文的重新加密，否则旧配置将无法解密。

启用外部模式后，当前问题、受上限约束的会话历史，以及当前授权工地的以下摘要会发送给服务商：工地名称、区域数、设备总数与启用设备在线/离线数、活动/高等级告警数、待复核风险数、待执行/执行中喷淋任务数和最近遥测时间；不包含具体环境监测指标。不要在问题或业务字段中放入超出用途所需的个人信息或秘密。浏览器读取脱敏后的状态；提交新 API Key 后应立即清空输入框，禁止保存到浏览器存储。

外部兼容响应会先按有界字节流读取，再做 JSON 反序列化；字节上限由 `AI_AGENT_MAX_RESPONSE_CHARS × 4 + 4096` 推导，并封顶为 132096 字节。非 2xx 响应正文不会被读取，超限或非法响应统一按 `502 AI_AGENT_PROVIDER_ERROR` 处理。修改以上配置后应重启后端，并先检查 `available=true`，再做隔离测试；这一步只证明配置可用，不等同于模型质量、安全性或生产验收。

## 4. 构建

依赖已安装时：

```powershell
.\scripts\build.ps1
```

全新机器：

```powershell
.\scripts\build.ps1 -InstallDependencies
```

需要真实 AI 模型依赖时才执行：

```powershell
.\scripts\build.ps1 -InstallDependencies -WithAiDependencies
```

该选项会在 `ai-service/.venv` 创建虚拟环境并安装 `requirements.txt`。模型权重较大且依赖可能需要联网；默认构建不会安装它们。

构建脚本的顺序是前端单元测试、模拟器协议测试、AI 安全模式测试、前端生产构建、后端测试与打包。任一步失败都会立即停止并返回非零退出码。

## 5. 默认 H2 单机部署

```powershell
Copy-Item .env.example .env
.\scripts\build.ps1
.\scripts\start.ps1
```

默认启动前端与后端。H2 文件位于 `runtime/sitesafe.mv.db`；首次空库会写入演示账号和确定性业务数据。

可选组件：

```powershell
.\scripts\start.ps1 -WithSimulator -WithAi
```

若已启动核心组件，再次执行 `start.ps1` 会拒绝覆盖有效 PID 记录。先执行 `stop.ps1`，或确认相应 PID 文件确实已过期。

## 6. MySQL 8 部署

### 6.1 创建数据库与账号

以具备管理权限的 MySQL 用户执行一次：

```sql
CREATE DATABASE db_sitesafe
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'sitesafe_app'@'127.0.0.1'
  IDENTIFIED BY '请替换为高强度随机密码';

GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
  ON db_sitesafe.* TO 'sitesafe_app'@'127.0.0.1';
FLUSH PRIVILEGES;
```

如果应用与 MySQL 不在同一主机，应将主机范围改为明确的应用服务器地址，不要使用 `%` 扩大授权范围。

### 6.2 启用 profile

```powershell
Copy-Item .env.mysql.example .env
notepad .env
```

至少替换 `DB_PASSWORD`；按实际地址调整 `DB_URL`。模板已经设置：

```text
SPRING_PROFILES_ACTIVE=mysql
DB_URL=jdbc:mysql://127.0.0.1:3306/db_sitesafe?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=false&useSSL=false
DB_USERNAME=sitesafe_app
```

然后启动：

```powershell
.\scripts\start.ps1
```

`application-mysql.yml` 会把 Spring SQL 初始化文件切换为 `classpath:schema-mysql.sql`。DDL 使用 `CREATE TABLE IF NOT EXISTS`。MySQL 模板默认设置 `DEMO_DATA_ENABLED=false`，不会植入固定演示账号；只有明确用于隔离的课堂演示库时才可临时改为 `true`。Spring 必须先连接到已存在的 `db_sitesafe`，所以 schema 脚本不会代替“创建数据库”步骤。

### 6.3 MySQL 运维注意事项

- 当前没有 Flyway/Liquibase 版本表；`schema-mysql.sql` 只适合初始结构和幂等补表，不承担复杂升级。
- 从 H2 切换 MySQL 不自动迁移已有数据。
- `DataInitializer` 仅在 `DEMO_DATA_ENABLED=true` 且 `app_user` 为空时写入基础种子数据；大型设备与扩展塔吊遥测补齐器在开关开启时会于每次启动执行，并通过稳定消息 ID 幂等补齐已有演示设备的数据。
- 上线前先备份，再在测试库演练任何 DDL 变化。
- 生产环境应启用 TLS，并从 `DB_URL` 移除 `useSSL=false`。

## 7. 启动脚本行为

`scripts/start.ps1` 会：

1. 解析并校验 `.env` 白名单；
2. 检查构建产物、运行时和端口；
3. 直接启动 Java、Node 和可选 Python 可执行文件，避免不可追踪的 shell 子进程；
4. 将标准输出/错误分别写入 `runtime/logs/`；
5. 记录 PID、进程名和 UTC 启动时间；
6. 等待 HTTP 健康检查；失败则只清理本次启动的进程。

指定其他环境文件：

```powershell
.\scripts\start.ps1 -EnvFile .env.mysql
```

## 8. 停止与日志

```powershell
.\scripts\stop.ps1
```

停止脚本按 AI、模拟器、前端、后端的逆序读取进程记录。只有当前 PID 的进程名与启动时间同时匹配时才会发送终止请求；记录已过期时只移除该记录，不会结束可能复用同一 PID 的其他程序。

日志目录：

```text
runtime/logs/backend.out.log
runtime/logs/backend.err.log
runtime/logs/frontend.out.log
runtime/logs/frontend.err.log
runtime/logs/simulator.out.log
runtime/logs/simulator.err.log
runtime/logs/ai.out.log
runtime/logs/ai.err.log
```

异常排查顺序：端口占用 → `.env` 语法 → JAR/`node_modules` 是否存在 → 数据库连接 → 对应组件错误日志。

## 9. PC 前端正式部署建议

正式环境不要长期运行 Vite 开发服务器。推荐：

1. 将 `frontend/dist/` 发布到 Nginx、IIS 或等价静态服务器；
2. SPA 未命中的路径回退到 `index.html`；
3. `/api/` 反向代理到 Spring Boot 8080；
4. `/ws/` 开启 WebSocket Upgrade 并代理到 8080；
5. 统一启用 HTTPS/WSS，浏览器只暴露 443；
6. 构建前设置 `VITE_API_BASE` 和 `VITE_WS_BASE`，或使用同源反向代理；
7. 只按 PC 视口验收，不把当前移动样式视为交付承诺。

后端应注册为 Windows 服务或由进程管理器托管，避免依赖交互式桌面会话。生产环境还必须补齐外部会话存储、密码管理、TCP 身份认证和事件租户隔离。

## 10. 端口与防火墙

本地演示仅绑定前端、模拟器和 AI 到 `127.0.0.1`。后端 HTTP 与 TCP 的默认绑定范围由 Spring Boot/`ServerSocket` 决定；不使用设备模拟器时设置 `TCP_ENABLED=false`。不要向不可信网络开放 9100、9200 或 5001。

生产建议只开放反向代理的 443；8080、数据库端口和设备接入端口应限制在应用或设备专用网段。

## 11. 备份与恢复

### H2

先停止应用，再复制 `runtime/sitesafe.mv.db` 到备份目录。不要在 Java 进程运行时直接复制或替换数据库文件。

### MySQL

使用组织批准的备份工具和加密存储；恢复时校验字符集为 `utf8mb4`、时区为 `Asia/Shanghai`，并执行接口健康、分页、遥测幂等和告警状态机回归。
