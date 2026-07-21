@echo off
REM 前端 Vite 启动脚本
cd /d %~dp0..\frontend
echo ========================================
echo  前端 Vite (端口 5173)
echo ========================================
npm run dev
pause
