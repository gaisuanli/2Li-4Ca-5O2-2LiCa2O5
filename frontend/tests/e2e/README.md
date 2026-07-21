# PC 浏览器 E2E

测试固定使用 Chromium 内核、`1440 × 900` 视口和单 worker，覆盖登录、设备分页、塔吊遥测与 GLB、告警查询及 AI Agent DEMO 问答。Windows 默认复用系统 Microsoft Edge；Linux/CI 默认使用 Playwright Chromium。

## 首次准备

项目要求 Node.js 20.19+ 或 22.12+。Windows 先在项目根目录完成构建即可：

```powershell
Set-Location D:\building-agent\platform
.\scripts\build.ps1 -InstallDependencies
```

Linux/CI 首次执行时再运行 `pnpm --dir frontend e2e:install` 安装 Chromium。

## 隔离执行（默认）

```powershell
pnpm --dir frontend test:e2e
```

Playwright 会自动启动后端和 Vite。后端使用一次性的 H2 内存库、确定性演示数据并关闭 TCP，因此每次执行互不污染；测试结束后进程会被回收。默认端口为 API `8080`、Web `5173`，可用 `E2E_API_PORT` 和 `E2E_WEB_PORT` 覆盖。

可用 `E2E_BROWSER_CHANNEL=msedge`、`chrome` 或其他 Playwright 支持的通道显式覆盖默认选择。

## 复用已启动服务

当本机服务已经由 `scripts/start.ps1` 启动时：

```powershell
$env:E2E_REUSE_SERVICES='true'
pnpm --dir frontend test:e2e
Remove-Item Env:E2E_REUSE_SERVICES
```

也可通过 `PLAYWRIGHT_BASE_URL` 指向已经配置好的站点；设置后 Playwright 不再启动任何本地服务。复用环境需要存在演示账号和业务基线数据。

## 调试与证据

```powershell
pnpm --dir frontend test:e2e:headed
pnpm --dir frontend test:e2e:ui
pnpm --dir frontend test:e2e:report
```

失败截图、错误清单和重试 trace 写入 `frontend/test-results/`；HTML 报告写入 `frontend/playwright-report/`。重试 trace 可在报告中直接打开。
