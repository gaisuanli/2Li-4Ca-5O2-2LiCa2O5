[CmdletBinding()]
param(
    [string]$AdminUrl = $env:MYSQL_SMOKE_ADMIN_URL,
    [string]$AdminUsername = $env:MYSQL_SMOKE_ADMIN_USERNAME,
    [string]$ApplicationHost = $env:MYSQL_SMOKE_APP_HOST,
    [ValidateRange(1, 1000)]
    [int]$MinimumTableCount = 20
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $repositoryRoot 'backend'
$maven = Get-Command mvn -ErrorAction SilentlyContinue

if ($null -eq $maven) {
    throw 'Maven (mvn) is required and was not found on PATH.'
}
if ([string]::IsNullOrWhiteSpace($AdminUrl)) {
    throw 'Set MYSQL_SMOKE_ADMIN_URL to a MySQL 8 JDBC URL before running this script.'
}
if ([string]::IsNullOrWhiteSpace($AdminUsername)) {
    throw 'Set MYSQL_SMOKE_ADMIN_USERNAME to a temporary-database administrator account.'
}

# Deliberately has no command-line parameter: CI or the current PowerShell
# process must inject the secret, so it is not copied into repository files or
# exposed in the Maven command line.
$adminPassword = [Environment]::GetEnvironmentVariable('MYSQL_SMOKE_ADMIN_PASSWORD', 'Process')
if ([string]::IsNullOrEmpty($adminPassword)) {
    throw 'Set MYSQL_SMOKE_ADMIN_PASSWORD to a non-empty value in the process environment.'
}
if ([string]::IsNullOrWhiteSpace($ApplicationHost)) {
    $ApplicationHost = '127.0.0.1'
}

$environmentNames = @(
    'MYSQL_SMOKE_ADMIN_URL',
    'MYSQL_SMOKE_ADMIN_USERNAME',
    'MYSQL_SMOKE_ADMIN_PASSWORD',
    'MYSQL_SMOKE_APP_HOST',
    'MYSQL_SMOKE_MIN_TABLES'
)
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    $env:MYSQL_SMOKE_ADMIN_URL = $AdminUrl
    $env:MYSQL_SMOKE_ADMIN_USERNAME = $AdminUsername
    $env:MYSQL_SMOKE_ADMIN_PASSWORD = $adminPassword
    $env:MYSQL_SMOKE_APP_HOST = $ApplicationHost
    $env:MYSQL_SMOKE_MIN_TABLES = [string]$MinimumTableCount

    Write-Host 'Running isolated MySQL 8 business smoke test.'
    Write-Host "Administrator URL: $AdminUrl"
    Write-Host "Disposable application account host: $ApplicationHost"
    Write-Host "Minimum expected table count: $MinimumTableCount"

    Push-Location $backendRoot
    try {
        & $maven.Source '-Dtest=MySqlBusinessSmokeIT' '-DfailIfNoTests=true' test
        if ($LASTEXITCODE -ne 0) {
            throw "MySQL business smoke test failed with Maven exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }

    Write-Host 'MySQL 8 empty-database initialization and business smoke test passed.' -ForegroundColor Green
}
finally {
    foreach ($name in $environmentNames) {
        $oldValue = $previousEnvironment[$name]
        if ($null -eq $oldValue) {
            [Environment]::SetEnvironmentVariable($name, $null, 'Process')
        }
        else {
            [Environment]::SetEnvironmentVariable($name, [string]$oldValue, 'Process')
        }
    }
}
