# 建筑安全智能监控平台

面向建筑工地监管人员的 PC 端实训项目。系统将工地、区域、设备、遥测、规则、告警、AI 风险、环境喷淋和审计记录组织成一条可演示的安全监管闭环。默认使用本地 H2 文件数据库，亦可切换到 MySQL 8。

> 本项目只以桌面浏览器为验收目标，建议视口不低于 `1366 × 768`。当前数据、视频状态和部分趋势为确定性演示内容，能力边界见[已知限制](docs/KNOWN_LIMITATIONS.md)。

## 模块划分

| 模块 | 目录 | 职责 | 默认端口 |
| --- | --- | --- | --- |
| PC 管理端 | `frontend/` | Vue 3 单页应用；监控、AI Agent、治理工作流和集成中心等十四个业务页 | 5173 |
| 业务 API | `backend/` | Spring Boot 3；认证与角色权限、规则/告警、知识/报告审核、外部适配器、审计、监控、WebSocket 和 TCP 接入 | HTTP 8080、TCP 9100 |
| 设备模拟器 | `device-simulator/` | 轮询塔吊、升降机、高支模和深基坑，按 4 字节大端长度 + UTF-8 JSON 协议上报 | 9200 |
| AI 适配器 | `ai-service/` | 提供健康检查、显式演示结果和可选模型推理接口；默认不启用模型 | 5001 |
| 数据库 | `backend/src/main/resources/`、`database/` | H2/MySQL 领域表结构；原始素材数据库仅作字段语义参考 | — |
| 交付脚本 | `scripts/` | Windows 下构建、启动和停止，使用 PID 与启动时间双重校验管理进程 | — |

更完整的职责边界和数据流见[系统架构](docs/ARCHITECTURE.md)。

## 快速开始（Windows PC）

### 1. 准备环境

- Windows 10/11、PowerShell 5.1 或 7；
- JDK 17 及 Maven 3.9+；
- Node.js 20.19+（或 22.12+）及 pnpm 11；
- Python 3.11+ 用于完整构建中的 AI 安全模式测试，以及启动 AI 适配器；若使用 `-SkipTests` 且不启动 AI 才可省略；
- MySQL 8 仅在切换 MySQL 配置时需要。

在项目根目录执行：

```powershell
Set-Location D:\building-agent\platform
Copy-Item .env.example .env
.\scripts\build.ps1
.\scripts\start.ps1
```

若 PowerShell 明确提示“禁止运行脚本”，请先确认 `scripts/` 内容来自本项目，再仅对当前终端会话执行 `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass` 后重试；关闭该终端即恢复原策略。

若全新环境尚未安装 JavaScript 依赖：

```powershell
.\scripts\build.ps1 -InstallDependencies
```

启动成功后访问：

| 地址 | 用途 |
| --- | --- |
| <http://127.0.0.1:5173> | PC 管理端 |
| <http://127.0.0.1:8080/api/health> | 后端健康检查 |
| <http://127.0.0.1:8080/actuator/health> | Actuator 健康检查 |
| `ws://127.0.0.1:8080/ws/events?token=...` | 实时事件通道 |

停止由脚本启动的进程：

```powershell
.\scripts\stop.ps1
```

脚本仅管理 `runtime/processes/` 中记录、且 PID 与启动时间均匹配的进程，不会按进程名称批量结束其他 Java、Node 或 Python 程序。运行日志位于 `runtime/logs/`。

## 演示账号

首次使用空数据库启动时，后端会创建以下本地演示账号：

| 角色 | 用户名 | 密码 | 主要权限 |
| --- | --- | --- | --- |
| 系统管理员 | `admin` | `Admin@123` | 全部页面、用户管理、全部业务操作、审计日志 |
| 项目监管员 | `supervisor` | `Safe@123` | 风险复核、告警处置、规则管理、喷淋控制 |
| 设备管理员 | `device` | `Device@123` | 设备新增/编辑/启停、遥测与 AI 风险写入、喷淋控制 |

这些口令只用于本地实训。切换到共享或长期环境前必须更换账号体系和口令。

## PC 页面与分页

左侧导航对应十四个独立路由模块：综合首页、设备总览、塔吊分析、大型设备、环境分析、视频监控、AI 风险、AI Agent 问答、告警中心、规则配置、用户管理、审计日志、知识与报告治理、集成中心。主要业务列表均采用服务端分页。

- 前端分页规格为每页 `10 / 20 / 50` 条；
- API 使用从 `1` 开始的 `page` 和 `pageSize`；
- 后端将 `pageSize` 限制在 `1–100`；
- 列表响应统一返回 `items`、`total`、`page`、`pageSize`；
- 查询条件变化后应回到第 1 页，翻页时保留当前筛选条件。

遥测趋势和环境趋势会先在服务端截取最近的数据点，再按时间升序返回；分页与长期时序数据的进一步限制见[已知限制](docs/KNOWN_LIMITATIONS.md)。

## 可选组件

启动设备模拟器：

```powershell
.\scripts\start.ps1 -WithSimulator
```

随后访问 <http://127.0.0.1:9200>，连接 TCP 并单次或自动发送塔吊数据。

启动默认关闭真实推理的 AI 适配器：

```powershell
.\scripts\start.ps1 -WithAi
```

健康检查地址为 <http://127.0.0.1:5001/health>。如需固定演示结果，在 `.env` 中设置 `AI_DEMO_MODE=true`；真实模型模式必须另外安装依赖并完成模型验证。

侧边栏“AI Agent 问答”属于主业务后端能力，与上述视觉模型适配器相互独立。它默认使用明确标注的本地 `DEMO` 回答。管理员可在 `.env` 中配置全局 OpenAI-compatible 服务：

```text
AI_AGENT_MODE=OPENAI_COMPATIBLE
AI_AGENT_MODEL=<服务商模型标识>
AI_AGENT_BASE_URL=https://<受信服务商>/v1
AI_AGENT_ALLOWED_BASE_URLS=https://<受信服务商>/v1
AI_AGENT_API_KEY=<仅由服务端读取的密钥>
```

也可以允许每位登录用户在“AI Agent 问答”页面自行填写 Base URL、模型和 API Key。先在 `.env` 中维护管理员允许的 Base URL，再执行一次：

```powershell
.\scripts\enable-agent-user-config.ps1
.\scripts\stop.ps1
.\scripts\start.ps1
```

该脚本只把随机生成的加密主密钥写入被 Git 忽略的本机 `.env`，不会输出密钥。用户 API Key 只在当前表单中短暂存在，后端使用 AES-256-GCM 加密保存，之后仅返回“已配置”状态，不回显明文或密文，也不写入浏览器存储、问答记录或审计详情。远程服务必须使用 HTTPS，Base URL 必须与管理员白名单精确匹配；全局 `DISABLED` 仍可一键停用外部问答。限流、并发、超时和数据发送边界见[部署说明](docs/DEPLOYMENT.md#3-ai-agent-外部-api-配置)。

## MySQL 切换

先按[部署说明](docs/DEPLOYMENT.md#6-mysql-8-部署)创建数据库和最小权限账号，再执行：

```powershell
Copy-Item .env.mysql.example .env
# 编辑 .env，替换 DB_PASSWORD
.\scripts\start.ps1
```

`mysql` profile 会使用 `schema-mysql.sql`，不会执行 H2 专用的 `schema.sql`。

MySQL 模板默认 `DEMO_DATA_ENABLED=false`，不会创建或补齐固定演示账号、基础业务数据、大型设备遥测及扩展塔吊遥测。只有隔离的课堂演示库才应显式开启该选项；共享或生产数据库必须保持关闭。

## 交付文档

- [系统架构](docs/ARCHITECTURE.md)
- [接口说明](docs/API.md)
- [测试与验收](docs/TESTING.md)
- [构建与部署](docs/DEPLOYMENT.md)
- [设备心跳超时与离线告警](docs/DEVICE_OFFLINE_MONITOR.md)
- [演示脚本](docs/DEMO_SCRIPT.md)
- [已知限制](docs/KNOWN_LIMITATIONS.md)
- [实训素材使用审计](docs/MATERIAL_USAGE_AUDIT.md)
- [第四轮治理与外部集成](docs/ROUND4_INTEGRATIONS.md)
- [MySQL 8 空库初始化与业务冒烟](docs/MYSQL_SMOKE.md)
- [`last.pt` 模型资源清单](docs/AI_MODEL_MANIFEST.md)
- [开发计划](DEVELOPMENT_PLAN.md)

## 素材来源说明

项目运行时直接使用实训压缩包中的塔吊 CSV 和 `tdcfv2.glb`；`last.pt` 只作为默认关闭、尚未验证的可选模型资源保留。字体已移出前端公开目录作为非运行时参考，工地场景底图是项目后续生成内容，并非来自该 ZIP。原始 SQL 与 Apifox 文件只供本地语义比对且由 `.gitignore` 隔离，禁止提交或打包；当前数据库模型和接口分别以 `schema.sql` / `schema-mysql.sql` 与 `docs/API.md` 为准。逐项证据、哈希和未采用原因见[实训素材使用审计](docs/MATERIAL_USAGE_AUDIT.md)，模型安全边界见[`last.pt` 模型资源清单](docs/AI_MODEL_MANIFEST.md)。
