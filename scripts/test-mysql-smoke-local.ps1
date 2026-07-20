[CmdletBinding()]
param(
    [string]$MySqlBase = 'C:\Program Files\MySQL\MySQL Server 8.0',
    [ValidateRange(1024, 65535)]
    [int]$Port = 33306,
    [ValidateRange(1, 1000)]
    [int]$MinimumTableCount = 20
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $repositoryRoot 'runtime'
$mysqld = Join-Path $MySqlBase 'bin\mysqld.exe'
$mysql = Join-Path $MySqlBase 'bin\mysql.exe'
$mysqlAdmin = Join-Path $MySqlBase 'bin\mysqladmin.exe'

foreach ($executable in @($mysqld, $mysql, $mysqlAdmin)) {
    if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) {
        throw "Required MySQL executable was not found: $executable"
    }
}

$versionOutput = & $mysqld --version 2>&1
if ($LASTEXITCODE -ne 0 -or [string]$versionOutput -notmatch '\bVer\s+8\.') {
    throw "MySQL 8 binaries are required. Detected: $versionOutput"
}

$portGuard = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
try {
    $portGuard.Start()
}
catch {
    throw "TCP port $Port is already in use. Choose another value with -Port."
}
finally {
    $portGuard.Stop()
}

$null = New-Item -ItemType Directory -Path $runtimeRoot -Force
$runtimeRoot = (Resolve-Path -LiteralPath $runtimeRoot).Path
$temporaryRoot = Join-Path $runtimeRoot ('mysql8-smoke-' + [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $temporaryRoot
$temporaryRoot = (Resolve-Path -LiteralPath $temporaryRoot).Path
if (-not $temporaryRoot.StartsWith(
        $runtimeRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to use an unsafe temporary MySQL path.'
}

$dataDirectory = Join-Path $temporaryRoot 'data'
$null = New-Item -ItemType Directory -Path $dataDirectory
$serverProcess = $null
$temporaryRootPassword = $null
$environmentNames = @(
    'MYSQL_SMOKE_ADMIN_URL',
    'MYSQL_SMOKE_ADMIN_USERNAME',
    'MYSQL_SMOKE_ADMIN_PASSWORD',
    'MYSQL_SMOKE_APP_HOST'
)
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    Write-Host "Initializing disposable MySQL 8 data directory under $temporaryRoot"
    & $mysqld --no-defaults --initialize-insecure "--basedir=$MySqlBase" "--datadir=$dataDirectory"
    if ($LASTEXITCODE -ne 0) {
        throw "mysqld --initialize-insecure failed with exit code $LASTEXITCODE."
    }

    $pidFile = Join-Path $temporaryRoot 'mysqld.pid'
    $errorLog = Join-Path $temporaryRoot 'mysqld.err'
    $serverArguments = @(
        '--no-defaults',
        ('--basedir="' + $MySqlBase + '"'),
        ('--datadir="' + $dataDirectory + '"'),
        "--port=$Port",
        '--bind-address=127.0.0.1',
        '--mysqlx=0',
        '--skip-log-bin',
        ('--pid-file="' + $pidFile + '"'),
        ('--log-error="' + $errorLog + '"')
    )
    $serverProcess = Start-Process `
        -FilePath $mysqld `
        -ArgumentList $serverArguments `
        -PassThru `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $temporaryRoot 'mysqld.out') `
        -RedirectStandardError (Join-Path $temporaryRoot 'mysqld.console.err')

    $ready = $false
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        $previousErrorPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        & $mysqlAdmin `
            --protocol=TCP `
            --host=127.0.0.1 `
            "--port=$Port" `
            --user=root `
            --skip-password `
            --silent ping 2>$null | Out-Null
        $pingExitCode = $LASTEXITCODE
        $ErrorActionPreference = $previousErrorPreference
        if ($pingExitCode -eq 0) {
            $ready = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) {
        throw 'Disposable MySQL 8 did not become ready within 60 seconds.'
    }

    $temporaryRootPassword = [Guid]::NewGuid().ToString('N') + 'Aa1!' + [Guid]::NewGuid().ToString('N')
    $previousErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    "ALTER USER 'root'@'localhost' IDENTIFIED BY '$temporaryRootPassword';" | & $mysql `
        --protocol=TCP `
        --host=127.0.0.1 `
        "--port=$Port" `
        --user=root `
        --skip-password `
        --batch 2>$null
    $passwordExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorPreference
    if ($passwordExitCode -ne 0) {
        throw 'Failed to assign the disposable MySQL administrator password.'
    }

    $env:MYSQL_SMOKE_ADMIN_URL = "jdbc:mysql://127.0.0.1:$Port/mysql?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
    $env:MYSQL_SMOKE_ADMIN_USERNAME = 'root'
    $env:MYSQL_SMOKE_ADMIN_PASSWORD = $temporaryRootPassword
    $env:MYSQL_SMOKE_APP_HOST = 'localhost'

    & (Join-Path $PSScriptRoot 'test-mysql-smoke.ps1') -MinimumTableCount $MinimumTableCount
}
finally {
    $actualServerProcess = $null
    $pidFile = Join-Path $temporaryRoot 'mysqld.pid'
    if (Test-Path -LiteralPath $pidFile) {
        $serverPidText = (Get-Content -LiteralPath $pidFile -Raw -ErrorAction SilentlyContinue).Trim()
        $serverPidNumber = 0
        if ([int]::TryParse($serverPidText, [ref]$serverPidNumber)) {
            $actualServerProcess = Get-Process -Id $serverPidNumber -ErrorAction SilentlyContinue
        }
    }
    if ($null -ne $actualServerProcess -and -not $actualServerProcess.HasExited) {
        $previousErrorPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        if (-not [string]::IsNullOrEmpty($temporaryRootPassword)) {
            $env:MYSQL_PWD = $temporaryRootPassword
            & $mysqlAdmin `
                --protocol=TCP `
                --host=127.0.0.1 `
                "--port=$Port" `
                --user=root shutdown 2>$null | Out-Null
            Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
        }
        else {
            & $mysqlAdmin `
                --protocol=TCP `
                --host=127.0.0.1 `
                "--port=$Port" `
                --user=root `
                --skip-password shutdown 2>$null | Out-Null
        }
        $ErrorActionPreference = $previousErrorPreference
        try {
            Wait-Process -Id $actualServerProcess.Id -Timeout 15 -ErrorAction Stop
        }
        catch {
            Stop-Process -Id $actualServerProcess.Id -Force -ErrorAction SilentlyContinue
        }
    }

    foreach ($name in $environmentNames) {
        $oldValue = $previousEnvironment[$name]
        if ($null -eq $oldValue) {
            [Environment]::SetEnvironmentVariable($name, $null, 'Process')
        }
        else {
            [Environment]::SetEnvironmentVariable($name, [string]$oldValue, 'Process')
        }
    }

    $cleanupPath = (Resolve-Path -LiteralPath $temporaryRoot -ErrorAction SilentlyContinue).Path
    if ($cleanupPath -and $cleanupPath.StartsWith(
            $runtimeRoot + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) {
        try {
            Remove-Item -LiteralPath $cleanupPath -Recurse -Force -ErrorAction Stop
        }
        catch {
            Write-Warning "Could not remove disposable MySQL directory $cleanupPath`: $($_.Exception.Message)"
        }
    }
}
