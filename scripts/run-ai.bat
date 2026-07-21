@echo off
REM AI   ≈‰∆˜∆Ù∂ØΩ≈±æ
cd /d %~dp0..\ai-service
set AI_MODE=qwen_vl
set QWEN_VL_API_KEY=sk-4c72dced871642c0a83e59b833547192
set QWEN_VL_MODEL=qwen-vl-max
set QWEN_VL_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
set AI_PORT=5001
echo ========================================
echo  AI   ≈‰∆˜ (∂Àø⁄ %AI_PORT%)
echo ========================================
python app.py
pause
