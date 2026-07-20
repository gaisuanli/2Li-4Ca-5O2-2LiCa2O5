[CmdletBinding()]
param(
    [string]$EnvFile = '.env',
    [switch]$WithSimulator,
    [switch]$WithAi
)

. (Join-Path $PSScriptRoot 'Common.ps1')

$root = Get-PlatformRoot
Import-SafeEnvironmentFile -Path $EnvFile -Root $root -Optional:($EnvFile -eq '.env')

$serverPort = Get-IntegerSetting -Name 'SERVER_PORT' -Default 8080
$tcpPort = Get-IntegerSetting -Name 'TCP_PORT' -Default 9100
$frontendPort = Get-IntegerSetting -Name 'FRONTEND_PORT' -Default 5173
$simulatorPort = Get-IntegerSetting -Name 'SIMULATOR_WEB_PORT' -Default 9200
$aiPort = Get-IntegerSetting -Name 'AI_PORT' -Default 5001
$tcpEnabledText = [Environment]::GetEnvironmentVariable('TCP_ENABLED', 'Process')
$tcpEnabled = [string]::IsNullOrWhiteSpace($tcpEnabledText) -or $tcpEnabledText.ToLowerInvariant() -eq 'true'

Assert-TcpPortAvailable -Port $serverPort -Component '后端 HTTP'
Assert-TcpPortAvailable -Port $frontendPort -Component 'PC 前端'
if ($tcpEnabled) {
    Assert-TcpPortAvailable -Port $tcpPort -Component '后端 TCP'
}
if ($WithSimulator) {
    Assert-TcpPortAvailable -Port $simulatorPort -Component '设备模拟器'
}
if ($WithAi) {
    Assert-TcpPortAvailable -Port $aiPort -Component 'AI 适配器'
}

$backendDirectory = Assert-ProjectPath -Path (Join-Path $root 'backend') -Root $root
$frontendDirectory = Assert-ProjectPath -Path (Join-Path $root 'frontend') -Root $root
$simulatorDirectory = Assert-ProjectPath -Path (Join-Path $root 'device-simulator') -Root $root
$aiDirectory = Assert-ProjectPath -Path (Join-Path $root 'ai-service') -Root $root
$jar = Get-ChildItem -LiteralPath (Join-Path $backendDirectory 'target') -Filter 'building-safety-api-*.jar' -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*.original' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($null -eq $jar) {
    throw '未找到后端可执行 JAR。请先执行 scripts\build.ps1。'
}

$vite = Assert-ProjectPath -Path (Join-Path $frontendDirectory 'node_modules\vite\bin\vite.js') -Root $root
if (-not (Test-Path -LiteralPath $vite -PathType Leaf)) {
    throw '未找到 PC 前端依赖。请先执行 scripts\build.ps1 -InstallDependencies。'
}

$java = Get-JavaRunner
$node = Get-RequiredCommandPath -Name 'node.exe'
Assert-SupportedNodeVersion -NodePath $node
$started = New-Object System.Collections.Generic.List[string]

try {
    $null = Start-ManagedProcess -Component 'backend' -FilePath $java `
        -Arguments @('-jar', $jar.FullName) -WorkingDirectory $root -Root $root
    $started.Add('backend')
    # The backend has already inherited its environment. Remove server-only secrets
    # before launching Node/Python helper processes so they cannot read database or
    # AI provider credentials from their inherited process environment.
    [Environment]::SetEnvironmentVariable('DB_PASSWORD', $null, 'Process')
    [Environment]::SetEnvironmentVariable('AI_AGENT_API_KEY', $null, 'Process')
    if (-not (Wait-HttpEndpoint -Uri "http://127.0.0.1:$serverPort/api/health" -TimeoutSeconds 60)) {
        throw '后端健康检查超时。'
    }

    $null = Start-ManagedProcess -Component 'frontend' -FilePath $node `
        -Arguments @($vite, '--configLoader', 'runner', '--host', '127.0.0.1', '--port', [string]$frontendPort) `
        -WorkingDirectory $frontendDirectory -Root $root
    $started.Add('frontend')
    if (-not (Wait-HttpEndpoint -Uri "http://127.0.0.1:$frontendPort/" -TimeoutSeconds 30)) {
        throw 'PC 前端健康检查超时。'
    }

    if ($WithSimulator) {
        $null = Start-ManagedProcess -Component 'simulator' -FilePath $node `
            -Arguments @((Join-Path $simulatorDirectory 'server.js')) `
            -WorkingDirectory $simulatorDirectory -Root $root
        $started.Add('simulator')
        if (-not (Wait-HttpEndpoint -Uri "http://127.0.0.1:$simulatorPort/" -TimeoutSeconds 30)) {
            throw '设备模拟器健康检查超时。'
        }
    }

    if ($WithAi) {
        $python = Get-PythonRunner -Root $root
        $null = Start-ManagedProcess -Component 'ai' -FilePath $python.FilePath `
            -Arguments @($python.PrefixArguments + @((Join-Path $aiDirectory 'app.py'))) `
            -WorkingDirectory $aiDirectory -Root $root
        $started.Add('ai')
        if (-not (Wait-HttpEndpoint -Uri "http://127.0.0.1:$aiPort/health" -TimeoutSeconds 30)) {
            throw 'AI 适配器健康检查超时。'
        }
    }

    Write-Host ''
    Write-Host '[就绪] 建筑安全智能监控平台已启动。' -ForegroundColor Green
    Write-Host "PC 管理端：http://127.0.0.1:$frontendPort"
    Write-Host "后端健康：http://127.0.0.1:$serverPort/api/health"
    if ($WithSimulator) { Write-Host "设备模拟器：http://127.0.0.1:$simulatorPort" }
    if ($WithAi) { Write-Host "AI 健康：http://127.0.0.1:$aiPort/health" }
    Write-Host '日志目录：runtime\logs'
}
catch {
    Write-Host "[失败] $($_.Exception.Message)" -ForegroundColor Red
    for ($index = $started.Count - 1; $index -ge 0; $index--) {
        Stop-TrackedComponent -Component $started[$index] -Root $root
    }
    exit 1
}
