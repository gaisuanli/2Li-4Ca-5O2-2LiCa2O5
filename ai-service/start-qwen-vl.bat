@echo off
REM 通义千问 VL 视觉 AI 适配器启动脚本
REM
REM 使用前请先设置环境变量 QWEN_VL_API_KEY
REM 申请地址：https://bailian.console.aliyun.com/?apiKey=1#/api-key

setlocal

REM ===== 配置（按需修改） =====
REM 通义千问 API Key（必填，从阿里云百炼控制台获取）
if "%QWEN_VL_API_KEY%"=="" set QWEN_VL_API_KEY=

REM 模型名称（qwen-vl-max 最强，qwen-vl-plus 更快更便宜）
if "%QWEN_VL_MODEL%"=="" set QWEN_VL_MODEL=qwen-vl-max

REM API 地址
if "%QWEN_VL_BASE_URL%"=="" set QWEN_VL_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1

REM 监听端口
if "%AI_PORT%"=="" set AI_PORT=5001

REM 推理模式
set AI_MODE=qwen_vl

REM ===== 启动 =====
cd /d "%~dp0"
echo ========================================
echo  通义千问 VL 视觉 AI 适配器
echo ========================================
echo  端口: %AI_PORT%
echo  模型: %QWEN_VL_MODEL%
echo  地址: %QWEN_VL_BASE_URL%
if "%QWEN_VL_API_KEY%"=="" (
  echo  警告: 未配置 QWEN_VL_API_KEY 环境变量
  echo  请编辑此脚本填入 API Key，或运行：
  echo    set QWEN_VL_API_KEY=sk-xxxxxxxx
  echo  然后重新运行此脚本
  pause
  exit /b 1
)
echo  API Key: %QWEN_VL_API_KEY:~0,8%...
echo ========================================
echo.

python app.py
pause
