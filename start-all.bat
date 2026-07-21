@echo off
REM ========================================
REM  建筑安全智能监控平台 - 一键启动脚本
REM  启动顺序：清理残留 -> AI 适配器 -> 后端 -> 前端
REM ========================================

setlocal

set ROOT=%~dp0
set SCRIPTS_DIR=%ROOT%scripts\

echo ========================================
echo  建筑安全智能监控平台 - 一键启动
echo ========================================
echo.

REM ===== 清理可能残留的进程 =====
echo  [0/3] 清理残留进程...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":5001 :8080 :9100 :5173" ^| findstr LISTENING') do (
    taskkill /F /T /PID %%a >nul 2>&1
)
timeout /t 2 /nobreak >nul
echo  清理完成
echo.

echo  [1/3] 启动 AI 适配器 (端口 5001)...
start "AI-Adapter" "%SCRIPTS_DIR%run-ai.bat"

timeout /t 3 /nobreak >nul

echo  [2/3] 启动后端 Spring Boot (端口 8080 + TCP 9100)...
start "Backend" "%SCRIPTS_DIR%run-backend.bat"

timeout /t 5 /nobreak >nul

echo  [3/3] 启动前端 Vite (端口 5173)...
start "Frontend" "%SCRIPTS_DIR%run-frontend.bat"

echo.
echo ========================================
echo  启动指令已发送，各服务窗口已打开
echo ========================================
echo.
echo  访问入口：
echo    前端首页:    http://localhost:5173/
echo    AI Agent:   http://localhost:5173/agent
echo    后端 API:   http://localhost:8080/api/health
echo    AI 适配器:  http://127.0.0.1:5001/health
echo    TCP 遥测:   127.0.0.1:9100
echo.
echo  注意事项：
echo    1. 请确保代理软件已在 1088 端口运行
echo    2. 后端启动较慢，请等待看到 Started SiteSafeApplication
echo    3. 要停止服务，运行 stop-all.bat
echo.
pause
