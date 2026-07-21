@echo off
REM ========================================
REM  建筑安全智能监控平台 - 一键停止脚本
REM  使用 /T 参数终止整个进程树（包括 Maven 启动的 Java、npm 启动的 node 子进程）
REM ========================================

echo ========================================
echo  停止所有服务...
echo ========================================
echo.

echo  [1/3] 停止 AI 适配器 (端口 5001)...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :5001 ^| findstr LISTENING') do (
    taskkill /F /T /PID %%a >nul 2>&1 && echo    已终止进程树 PID %%a
)

echo  [2/3] 停止后端 Spring Boot (端口 8080 + TCP 9100)...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8080 ^| findstr LISTENING') do (
    taskkill /F /T /PID %%a >nul 2>&1 && echo    已终止进程树 PID %%a
)
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :9100 ^| findstr LISTENING') do (
    taskkill /F /T /PID %%a >nul 2>&1 && echo    已终止进程树 PID %%a
)

echo  [3/3] 停止前端 Vite (端口 5173)...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :5173 ^| findstr LISTENING') do (
    taskkill /F /T /PID %%a >nul 2>&1 && echo    已终止进程树 PID %%a
)

echo.
echo  等待端口释放 (3 秒)...
timeout /t 3 /nobreak >nul

echo.
echo ========================================
echo  所有服务已停止
echo ========================================
echo.
pause
