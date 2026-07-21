@echo off
REM 后端 Spring Boot 启动脚本
REM 使用 mvn package 打 jar + java -jar 启动，避免 spring-boot:run 的 classpath 问题
REM 代理参数已在 SiteSafeApplication.java 中通过 System.setProperty 配置
cd /d %~dp0..\backend
echo ========================================
echo  后端 Spring Boot (端口 8080 + TCP 9100)
echo ========================================

REM ===== 注入敏感配置（不写入 application.yml） =====
REM DeepSeek API Key（通过环境变量传给 JVM，application.yml 中用 ${AI_AGENT_API_KEY} 读取）
set AI_AGENT_API_KEY=sk-84ce9be81db148729e9baa303d35130c

REM 查找已有的 jar，如果存在直接启动
for %%f in (target\building-safety-api-*.jar) do (
    if not "%%~nxf"=="%%~nf.jar.original" (
        set JAR_FILE=%%f
    )
)

if defined JAR_FILE (
    echo  使用已有 jar: %JAR_FILE%
    echo  如需重新编译，请删除 target 目录后重新运行此脚本
    echo.
    java -jar %JAR_FILE%
    pause
    exit /b
)

REM 没有 jar，先打包
echo  未找到可执行 jar，开始打包...
echo.
call mvn clean package -DskipTests
if errorlevel 1 (
    echo.
    echo  打包失败，请检查编译错误
    pause
    exit /b 1
)

REM 查找打包后的 jar
set JAR_FILE=
for %%f in (target\building-safety-api-*.jar) do (
    if not "%%~nxf"=="%%~nf.jar.original" (
        set JAR_FILE=%%f
    )
)

if not defined JAR_FILE (
    echo.
    echo  未找到打包后的 jar 文件
    pause
    exit /b 1
)

echo.
echo  启动 jar: %JAR_FILE%
echo.
java -jar %JAR_FILE%
pause
