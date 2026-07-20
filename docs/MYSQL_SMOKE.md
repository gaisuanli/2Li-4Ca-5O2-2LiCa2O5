# MySQL 8 空库初始化与业务冒烟

## 目标

`scripts/test-mysql-smoke.ps1` 面向一台已经运行的真实 MySQL 8 服务，自动完成以下工作：

1. 使用管理员连接创建名称随机、彼此隔离的空数据库；
2. 创建随机密码的临时应用账号，并仅授予该数据库的业务 DML 与建表权限；
3. 以 `mysql` Spring profile 启动测试上下文，由 `schema-mysql.sql` 初始化数据库；
4. 从 `schema-mysql.sql` 动态提取应建表集合，校验 MySQL 主版本、最小表数量、InnoDB 引擎及 schema 重复执行；
5. 通过真实 Spring Security、Controller、Service 和 JDBC 链路冒烟登录、设备、遥测、自动告警、告警处置、AI 风险、Agent 对话、知识发布、报告审核/投递和喷淋任务；
6. 关闭连接池，并删除临时数据库和临时账号。

每次运行使用新的数据库和账号，因此不会读取、覆盖或依赖现有业务库。测试不需要 MySQL 命令行客户端，MySQL JDBC 驱动由后端 Maven 依赖提供。

## 前置条件

- JDK 17 或更高版本；
- Maven 3.9 或更高版本；
- 可访问的 MySQL 8.x 服务，或本机已安装的 MySQL 8 Server 二进制；
- 使用已有/远程服务时，还需一个只用于验收、能够执行 `CREATE/DROP DATABASE`、`CREATE/DROP USER` 和 `GRANT` 的管理员账号。本机一次性模式不需要已有账号凭据。

不要对生产数据库服务器直接运行此脚本。建议使用本机、测试虚拟机或 CI 中的隔离 MySQL 8 实例。

## 本机一次性 MySQL 8（推荐）

Windows 已安装 MySQL 8 Server 二进制时，可以完全不使用现有服务和已有 root 密码：

```powershell
./scripts/test-mysql-smoke-local.ps1
```

该脚本在 `runtime/` 下执行 `mysqld --initialize-insecure`，只监听 `127.0.0.1:33306`，立即设置运行时随机管理员密码，再调用主冒烟脚本。结束时它会停止该临时进程并删除整个临时数据目录；正在运行的 `MySQL80` Windows 服务、Workbench 连接和已有数据库均不会被修改。

默认路径为 `C:\Program Files\MySQL\MySQL Server 8.0`。路径或端口不同时显式指定：

```powershell
./scripts/test-mysql-smoke-local.ps1 `
  -MySqlBase 'D:\tools\mysql-8.0' `
  -Port 33316
```

本地临时服务为了完成 `caching_sha2_password` 首次认证，在仅回环、短生命周期的 JDBC URL 中设置 `allowPublicKeyRetrieval=true`。远程或长期环境不要照搬，应使用 TLS 和组织批准的证书配置。

## 非交互运行

所有命令均从 `platform` 根目录执行。密码只从当前进程环境读取，不接受脚本参数，也不写入 `.env`、日志或版本库：

```powershell
$env:MYSQL_SMOKE_ADMIN_URL = 'jdbc:mysql://127.0.0.1:3306/mysql?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
$env:MYSQL_SMOKE_ADMIN_USERNAME = 'root'
$env:MYSQL_SMOKE_ADMIN_PASSWORD = '<由 CI 密钥或本机密钥管理器注入>'

./scripts/test-mysql-smoke.ps1
```

执行结束后可从当前 shell 删除凭据：

```powershell
Remove-Item Env:MYSQL_SMOKE_ADMIN_PASSWORD -ErrorAction SilentlyContinue
```

CI 应通过受保护变量注入 `MYSQL_SMOKE_ADMIN_PASSWORD`，不要把密码写进 YAML、`.env.mysql.example`、命令行参数或测试源码。

上例的非 TLS 配置只适用于隔离的本机回环验收。连接远程 MySQL 时必须按组织规范启用 TLS，并禁止不受信公钥检索。

## 可选参数

默认临时 MySQL 账号限定为 `'随机账号'@'127.0.0.1'`。如果 MySQL 服务器识别到的测试客户端来源不同，应明确设置准确的账号 host；不要为了省事长期使用 `%`：

```powershell
$env:MYSQL_SMOKE_APP_HOST = 'localhost'
./scripts/test-mysql-smoke.ps1
```

测试从当前 `schema-mysql.sql` 动态提取全部表名，不硬编码最终表数；14 只是防止 schema 意外退化的最低基线，知识库、报告等后续扩展会自动纳入检查：

```powershell
./scripts/test-mysql-smoke.ps1 -MinimumTableCount 20
```

管理员 URL 和用户名也可作为非敏感参数传入：

```powershell
./scripts/test-mysql-smoke.ps1 `
  -AdminUrl 'jdbc:mysql://127.0.0.1:3306/mysql?serverTimezone=Asia/Shanghai&useSSL=false' `
  -AdminUsername 'sitesafe_smoke_admin'
```

## 验收覆盖

| 范围 | 验收点 |
| --- | --- |
| 独立空库 | 每次随机创建 `sitesafe_smoke_*`，不复用已有 schema |
| MySQL profile | 活跃 profile 为 `mysql`，驱动连接到 MySQL 8，catalog 为本次临时库 |
| schema | 至少 20 表、当前 SQL 声明的全部表齐全且均为 InnoDB、`schema-mysql.sql` 二次执行不增表且不报错 |
| 登录 | `admin`、`supervisor`、`device` 三种演示身份经过真实密码校验获取 token |
| 设备 | 管理员新增环境设备并读回生成 ID、离线初始状态和配置 |
| 遥测与告警 | 设备账号上报塔吊超阈值遥测；验证幂等、防重复入库和自动生成告警 |
| 告警处置 | 监管员执行 `CONFIRM → RESOLVE → CLOSE` 完整状态机 |
| AI 风险 | 新增视觉风险、人工确认并自动生成风险告警 |
| Agent | 创建会话并在隔离的 `DEMO` 模式完成一轮问答和消息持久化，不访问外部模型 |
| 治理工作流 | 知识草稿提交并由管理员发布；从模板生成报告、提交审核、批准后通过 LOG 渠道投递 |
| 喷淋 | 创建、下发、成功回执，最终状态为 `EXECUTED` |
| 审计 | 核心写操作产生带 trace ID 的审计记录 |

## 失败与清理

正常成功、断言失败和常规 JVM 退出都会尝试删除临时数据库与账号。若 MySQL 服务在清理阶段不可用，测试会输出临时数据库名称以及警告；管理员恢复服务后应显式执行：

```sql
DROP DATABASE IF EXISTS `sitesafe_smoke_<运行时后缀>`;
DROP USER IF EXISTS 'ss_smoke_<运行时后缀>'@'127.0.0.1';
```

常见失败定位：

- `Access denied`：检查管理员权限，或将 `MYSQL_SMOKE_APP_HOST` 调整为 MySQL 实际识别的精确客户端来源；
- `Communications link failure`：检查地址、端口、防火墙和 MySQL 监听范围；
- `A MySQL 8 server is required`：该验收故意拒绝 H2、MariaDB 和其他 MySQL 主版本；
- schema 初始化失败：查看 Maven 输出中的首个 DDL 错误，不要开启 `continue-on-error` 掩盖问题；
- 清理警告：记录输出中的随机库名，恢复管理员连接后手工清理。

## 与常规测试的关系

真实 MySQL 测试类名为 `MySqlBusinessSmokeIT`，不会被普通 `mvn test` 自动发现；常规开发测试仍使用隔离 H2。只有显式执行脚本或以下命令才会访问 MySQL：

```powershell
Set-Location backend
mvn -Dtest=MySqlBusinessSmokeIT test
```

直接执行 Maven 时仍必须提前设置四个 `MYSQL_SMOKE_*` 环境变量。推荐使用包装脚本，以获得参数校验和一致的表数量基线。
