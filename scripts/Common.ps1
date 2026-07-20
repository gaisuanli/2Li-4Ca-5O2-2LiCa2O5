Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-PlatformRoot {
    return [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
}

function Assert-ProjectPath {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Root
    )

    $fullPath = [IO.Path]::GetFullPath($Path)
    $fullRoot = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $prefix = $fullRoot + [IO.Path]::DirectorySeparatorChar
    if ($fullPath -ne $fullRoot -and -not $fullPath.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "路径超出项目目录：$fullPath"
    }
    return $fullPath
}

function Get-RequiredCommandPath {
    param([Parameter(Mandatory = $true)][string]$Name)

    $command = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $command) {
        throw "未找到必需命令：$Name"
    }
    return $command.Source
}

function Get-JavaRunner {
    $launcher = Get-RequiredCommandPath -Name 'java.exe'
    $previousPreference = $ErrorActionPreference
    try {
        # java writes version/property information to stderr even on success.
        # PowerShell 5 can promote that stream to an ErrorRecord when Stop is
        # active, so capture it under Continue and validate the native exit code.
        $ErrorActionPreference = 'Continue'
        $settings = (& $launcher '-XshowSettings:properties' '-version' 2>&1 | Out-String)
        $javaExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($javaExitCode -ne 0) {
        throw '无法读取 Java 运行时信息。'
    }

    $javaHomeMatch = [regex]::Match($settings, '(?m)^\s*java\.home\s*=\s*(.+?)\s*$')
    if ($javaHomeMatch.Success) {
        $resolved = Join-Path $javaHomeMatch.Groups[1].Value.Trim() 'bin\java.exe'
        if (Test-Path -LiteralPath $resolved -PathType Leaf) {
            return [IO.Path]::GetFullPath($resolved)
        }
    }

    # Some Java distributions expose the real binary directly and do not print
    # java.home in the expected form. Keep that direct executable as fallback.
    return $launcher
}

function Assert-SupportedNodeVersion {
    param([Parameter(Mandatory = $true)][string]$NodePath)

    $rawVersion = (& $NodePath '--version' 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $rawVersion -notmatch '^v(\d+)\.(\d+)\.(\d+)$') {
        throw "无法识别 Node.js 版本：$rawVersion"
    }

    $major = [int]$Matches[1]
    $minor = [int]$Matches[2]
    $supported = ($major -eq 20 -and $minor -ge 19) -or `
        ($major -eq 22 -and $minor -ge 12) -or `
        ($major -gt 22)
    if (-not $supported) {
        throw "当前 Node.js 为 $rawVersion；Vite 7 需要 Node.js 20.19+ 或 22.12+。"
    }
    Write-Host "[环境] Node.js $rawVersion" -ForegroundColor DarkGray
}

function Assert-SupportedPythonVersion {
    param(
        [Parameter(Mandatory = $true)][string]$PythonPath,
        [string[]]$PrefixArguments = @()
    )

    $versionArguments = @($PrefixArguments + @('--version'))
    $rawVersion = (& $PythonPath @versionArguments 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $rawVersion -notmatch '^Python (\d+)\.(\d+)\.(\d+)') {
        throw "无法识别 Python 版本：$rawVersion"
    }

    $major = [int]$Matches[1]
    $minor = [int]$Matches[2]
    if ($major -ne 3 -or $minor -lt 11) {
        throw "当前 Python 为 $rawVersion；AI 适配器需要 Python 3.11 或更高版本。"
    }
    Write-Host "[环境] $rawVersion" -ForegroundColor DarkGray
}

function Get-PythonRunner {
    param([Parameter(Mandatory = $true)][string]$Root)

    $venvPython = Join-Path $Root 'ai-service\.venv\Scripts\python.exe'
    if (Test-Path -LiteralPath $venvPython -PathType Leaf) {
        Assert-SupportedPythonVersion -PythonPath $venvPython
        return [pscustomobject]@{ FilePath = $venvPython; PrefixArguments = @() }
    }

    $python = Get-Command python.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $python) {
        Assert-SupportedPythonVersion -PythonPath $python.Source
        return [pscustomobject]@{ FilePath = $python.Source; PrefixArguments = @() }
    }

    $launcher = Get-Command py.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $launcher) {
        Assert-SupportedPythonVersion -PythonPath $launcher.Source -PrefixArguments @('-3')
        return [pscustomobject]@{ FilePath = $launcher.Source; PrefixArguments = @('-3') }
    }

    throw '未找到 Python 3。AI 适配器需要 Python 3.11 或更高版本。'
}

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$Label
    )

    Write-Host "[执行] $Label" -ForegroundColor Cyan
    Push-Location -LiteralPath $WorkingDirectory
    try {
        & $FilePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "$Label 失败，退出码：$LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}

function Import-SafeEnvironmentFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Root,
        [switch]$Optional
    )

    $resolved = Assert-ProjectPath -Path (Join-Path $Root $Path) -Root $Root
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
        if ($Optional) {
            return
        }
        throw "环境文件不存在：$resolved"
    }

    $allowedNames = @(
        'SERVER_PORT', 'TCP_ENABLED', 'TCP_PORT', 'TOKEN_TTL_MINUTES', 'CORS_ORIGINS',
        'DEMO_DATA_ENABLED',
        'SPRING_PROFILES_ACTIVE', 'DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'DB_DRIVER',
        'FRONTEND_PORT', 'SIMULATOR_WEB_PORT', 'TCP_HOST', 'AI_PORT', 'AI_DEMO_MODE',
        'AI_ENABLE_MODEL', 'VITE_API_BASE', 'VITE_WS_BASE',
        'DEVICE_OFFLINE_MONITOR_ENABLED', 'DEVICE_OFFLINE_TIMEOUT_SECONDS',
        'DEVICE_OFFLINE_SCAN_INTERVAL_MS', 'DEVICE_OFFLINE_INITIAL_DELAY_MS',
        'DEVICE_OFFLINE_BATCH_SIZE',
        'SPRINKLER_MINIMUM_INTERVAL_SECONDS', 'SPRINKLER_DISPATCH_TIMEOUT_SECONDS',
        'SPRINKLER_TIMEOUT_SCAN_MS', 'SPRINKLER_TIMEOUT_SCAN_ENABLED',
        'AI_AGENT_MODE', 'AI_AGENT_MODEL', 'AI_AGENT_BASE_URL', 'AI_AGENT_API_KEY',
        'AI_AGENT_ALLOWED_BASE_URLS', 'AI_AGENT_CONNECT_TIMEOUT_MS',
        'AI_AGENT_READ_TIMEOUT_MS', 'AI_AGENT_MAX_CONTENT_CHARS',
        'AI_AGENT_MAX_RESPONSE_CHARS', 'AI_AGENT_MAX_HISTORY_MESSAGES',
        'AI_AGENT_CONVERSATION_LOCK_STRIPES', 'AI_AGENT_MAX_CONCURRENT_REQUESTS',
        'AI_AGENT_PER_USER_REQUESTS_PER_MINUTE', 'AI_AGENT_BULKHEAD_WAIT_MS',
        'AI_AGENT_CONVERSATION_LOCK_WAIT_MS',
        'AI_AGENT_USER_CONFIG_ENABLED', 'AI_AGENT_CREDENTIAL_ENCRYPTION_KEY',
        'AI_AGENT_APPROVED_MODELS'
    )

    $lineNumber = 0
    foreach ($rawLine in Get-Content -LiteralPath $resolved -Encoding UTF8) {
        $lineNumber++
        $line = $rawLine.Trim()
        if ($line.Length -eq 0 -or $line.StartsWith('#')) {
            continue
        }
        if ($line -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') {
            throw "环境文件第 $lineNumber 行不是 KEY=VALUE 格式。"
        }
        $name = $Matches[1]
        $value = $Matches[2].Trim()
        if ($allowedNames -notcontains $name) {
            throw "环境文件第 $lineNumber 行包含未允许变量：$name"
        }
        if ($value.Length -ge 2) {
            $first = $value.Substring(0, 1)
            $last = $value.Substring($value.Length - 1, 1)
            if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }
        if ($value.IndexOf([char]0) -ge 0) {
            throw "环境文件第 $lineNumber 行包含非法空字符。"
        }
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
    Write-Host "[环境] 已加载 $resolved" -ForegroundColor DarkGray
}

function Get-IntegerSetting {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][int]$Default,
        [int]$Minimum = 1,
        [int]$Maximum = 65535
    )

    $text = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if ([string]::IsNullOrWhiteSpace($text)) {
        return $Default
    }
    $parsed = 0
    if (-not [int]::TryParse($text, [ref]$parsed) -or $parsed -lt $Minimum -or $parsed -gt $Maximum) {
        throw "$Name 必须是 $Minimum 到 $Maximum 之间的整数。"
    }
    return $parsed
}

function Test-TcpPortAvailable {
    param([Parameter(Mandatory = $true)][int]$Port)

    $listener = $null
    try {
        $listener = New-Object -TypeName System.Net.Sockets.TcpListener -ArgumentList ([Net.IPAddress]::Loopback, $Port)
        $listener.Server.ExclusiveAddressUse = $true
        $listener.Start()
        return $true
    }
    catch {
        return $false
    }
    finally {
        if ($null -ne $listener) {
            $listener.Stop()
        }
    }
}

function Assert-TcpPortAvailable {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$Component
    )

    if (-not (Test-TcpPortAvailable -Port $Port)) {
        throw "$Component 所需端口 $Port 已被占用。"
    }
}

function Get-RuntimePaths {
    param([Parameter(Mandatory = $true)][string]$Root)

    $runtime = Assert-ProjectPath -Path (Join-Path $Root 'runtime') -Root $Root
    $processes = Assert-ProjectPath -Path (Join-Path $runtime 'processes') -Root $Root
    $logs = Assert-ProjectPath -Path (Join-Path $runtime 'logs') -Root $Root
    New-Item -ItemType Directory -Force -Path $processes, $logs | Out-Null
    return [pscustomobject]@{ Runtime = $runtime; Processes = $processes; Logs = $logs }
}

function Get-TrackedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$Component,
        [Parameter(Mandatory = $true)][string]$Root
    )

    $paths = Get-RuntimePaths -Root $Root
    $recordPath = Assert-ProjectPath -Path (Join-Path $paths.Processes "$Component.json") -Root $Root
    if (-not (Test-Path -LiteralPath $recordPath -PathType Leaf)) {
        return $null
    }

    try {
        $record = Get-Content -LiteralPath $recordPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $process = Get-Process -Id ([int]$record.pid) -ErrorAction Stop
        $expectedStart = [DateTime]::Parse(
            [string]$record.startTimeUtc,
            [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::AdjustToUniversal
        ).ToUniversalTime()
        $actualStart = $process.StartTime.ToUniversalTime()
        $sameStart = [Math]::Abs(($actualStart - $expectedStart).TotalSeconds) -lt 2
        $sameName = $process.ProcessName -eq [string]$record.processName
        if (-not $sameStart -or -not $sameName) {
            Write-Warning "$Component 的 PID 记录已过期；为避免误结束其他进程，仅移除记录。"
            Remove-Item -LiteralPath $recordPath -Force
            return $null
        }
        return [pscustomobject]@{ Process = $process; Record = $record; RecordPath = $recordPath }
    }
    catch [Microsoft.PowerShell.Commands.ProcessCommandException] {
        Remove-Item -LiteralPath $recordPath -Force
        return $null
    }
    catch {
        throw "无法安全验证 $Component 进程记录：$($_.Exception.Message)"
    }
}

function Start-ManagedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$Component,
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$Root
    )

    if ($null -ne (Get-TrackedProcess -Component $Component -Root $Root)) {
        throw "$Component 已由交付脚本启动。请先执行 scripts\stop.ps1。"
    }

    $paths = Get-RuntimePaths -Root $Root
    $stdout = Assert-ProjectPath -Path (Join-Path $paths.Logs "$Component.out.log") -Root $Root
    $stderr = Assert-ProjectPath -Path (Join-Path $paths.Logs "$Component.err.log") -Root $Root
    $recordPath = Assert-ProjectPath -Path (Join-Path $paths.Processes "$Component.json") -Root $Root

    $process = Start-Process -FilePath $FilePath -ArgumentList $Arguments -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    Start-Sleep -Milliseconds 500
    $process.Refresh()
    if ($process.HasExited) {
        throw "$Component 启动后立即退出。请查看 $stderr"
    }

    $record = [ordered]@{
        component = $Component
        pid = $process.Id
        processName = $process.ProcessName
        startTimeUtc = $process.StartTime.ToUniversalTime().ToString('o')
        executable = $FilePath
        workingDirectory = $WorkingDirectory
    }
    $record | ConvertTo-Json | Set-Content -LiteralPath $recordPath -Encoding UTF8
    Write-Host "[启动] $Component，PID $($process.Id)" -ForegroundColor Green
    return $process
}

function Stop-TrackedComponent {
    param(
        [Parameter(Mandatory = $true)][string]$Component,
        [Parameter(Mandatory = $true)][string]$Root
    )

    $tracked = Get-TrackedProcess -Component $Component -Root $Root
    if ($null -eq $tracked) {
        Write-Host "[跳过] $Component 未由交付脚本运行。" -ForegroundColor DarkGray
        return
    }

    $process = $tracked.Process
    try {
        Stop-Process -Id $process.Id -ErrorAction Stop
        $process.WaitForExit(5000) | Out-Null
        if (-not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction Stop
        }
        Write-Host "[停止] $Component，PID $($process.Id)" -ForegroundColor Yellow
    }
    finally {
        if (Test-Path -LiteralPath $tracked.RecordPath -PathType Leaf) {
            Remove-Item -LiteralPath $tracked.RecordPath -Force
        }
    }
}

function Wait-HttpEndpoint {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [int]$TimeoutSeconds = 45
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
                return $true
            }
        }
        catch {
            Start-Sleep -Milliseconds 500
        }
    }
    return $false
}
