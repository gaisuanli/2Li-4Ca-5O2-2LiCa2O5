[CmdletBinding()]
param(
    [string]$OutputPath = 'database\snapshot\sitesafe-h2-full.sql'
)

. (Join-Path $PSScriptRoot 'Common.ps1')

$root = Get-PlatformRoot
$resolvedOutput = Assert-ProjectPath -Path (Join-Path $root $OutputPath) -Root $root
$snapshotRoot = [IO.Path]::GetFullPath((Join-Path $root 'database\snapshot')).TrimEnd([IO.Path]::DirectorySeparatorChar)
$snapshotPrefix = $snapshotRoot + [IO.Path]::DirectorySeparatorChar
if (-not $resolvedOutput.StartsWith($snapshotPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Database snapshots may only be written under database\snapshot.'
}

$h2Home = Join-Path $env:USERPROFILE '.m2\repository\com\h2database\h2'
$h2Jar = Get-ChildItem -LiteralPath $h2Home -Recurse -Filter 'h2-*.jar' -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($null -eq $h2Jar) {
    throw 'H2 tools were not found. Run the Maven build to download backend dependencies first.'
}

$outputDirectory = Split-Path -Parent $resolvedOutput
if (-not (Test-Path -LiteralPath $outputDirectory -PathType Container)) {
    $null = New-Item -ItemType Directory -Path $outputDirectory
}

$temporaryName = 'h2-snapshot-' + [Guid]::NewGuid().ToString('N') + '.sql'
$temporaryPath = Assert-ProjectPath -Path (Join-Path $root ('runtime\' + $temporaryName)) -Root $root
$databaseUrl = 'jdbc:h2:file:' + (($root -replace '\\', '/') + '/runtime/sitesafe') +
    ';MODE=MySQL;DATABASE_TO_LOWER=FALSE;DATABASE_TO_UPPER=FALSE;' +
    'CASE_INSENSITIVE_IDENTIFIERS=TRUE;AUTO_SERVER=TRUE'
$temporaryForSql = $temporaryPath -replace '\\', '/'
$scriptSql = "SCRIPT SIMPLE COLUMNS NOPASSWORDS NOSETTINGS TO '$temporaryForSql'"
$java = Get-JavaRunner

try {
    $previousPreference = $ErrorActionPreference
    try {
        # H2 Shell returns the generated statements as a result set as well as
        # writing the file. Discard that stream so business data never reaches
        # the terminal or CI logs.
        $ErrorActionPreference = 'Continue'
        & $java '-cp' $h2Jar.FullName 'org.h2.tools.Shell' `
            '-url' $databaseUrl '-user' 'sa' '-sql' $scriptSql 2>&1 | Out-Null
        $javaExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($javaExitCode -ne 0 -or -not (Test-Path -LiteralPath $temporaryPath -PathType Leaf)) {
        throw 'H2 logical export failed. This script supports the default local H2 database with an empty database password.'
    }

    $lines = @(Get-Content -LiteralPath $temporaryPath -Encoding UTF8)
    $credentialRows = @($lines | Where-Object {
        $_ -match '^INSERT INTO "PUBLIC"\."ai_agent_provider_config"'
    })
    if ($credentialRows.Count -gt 0) {
        throw 'Personal AI provider credential rows exist. Clear those configurations before exporting so ciphertext never enters Git.'
    }

    $secretPatterns = @(
        'ghp_[A-Za-z0-9]{20,}',
        'github_pat_[A-Za-z0-9_]{20,}',
        'sk-[A-Za-z0-9_-]{20,}',
        'AKIA[0-9A-Z]{16}',
        '-----BEGIN [A-Z ]*PRIVATE KEY-----',
        '(?i)Bearer\s+[A-Za-z0-9._~+/-]{20,}'
    )
    foreach ($pattern in $secretPatterns) {
        $matches = @($lines | Select-String -Pattern $pattern)
        if ($matches.Count -gt 0) {
            $lineNumbers = ($matches | ForEach-Object LineNumber) -join ', '
            throw "Database free text may contain a secret (lines $lineNumbers); snapshot generation was refused."
        }
    }

    $safeLines = @($lines | Where-Object {
        $_ -notmatch '^CREATE USER IF NOT EXISTS '
    } | ForEach-Object {
        # H2 pads generated statements with spaces. Normalize the logical
        # export so repeated snapshots remain reviewable and pass Git checks.
        $_.TrimEnd()
    })
    $utf8NoBom = New-Object Text.UTF8Encoding($false)
    # The manifest hashes the exact bytes committed to Git. Always emit LF so
    # Windows core.autocrlf cannot make the index blob differ from this hash.
    $snapshotText = ([string[]]$safeLines -join "`n") + "`n"
    [IO.File]::WriteAllText($resolvedOutput, $snapshotText, $utf8NoBom)

    $rowCounts = [ordered]@{}
    foreach ($line in $safeLines) {
        if ($line -match '^--\s+(\d+)\s+\+/-\s+SELECT COUNT\(\*\) FROM PUBLIC\.([A-Za-z0-9_]+);') {
            $rowCounts[$Matches[2]] = [int]$Matches[1]
        }
    }
    $hash = (Get-FileHash -LiteralPath $resolvedOutput -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifest = [ordered]@{
        format = 'h2-logical-snapshot-v1'
        exportedAt = [DateTimeOffset]::Now.ToString('o')
        source = 'runtime/sitesafe.mv.db'
        sqlFile = [IO.Path]::GetFileName($resolvedOutput)
        sha256 = $hash
        tableCount = $rowCounts.Count
        totalRows = ($rowCounts.Values | Measure-Object -Sum).Sum
        rowCounts = $rowCounts
        security = [ordered]@{
            databaseUserCredentialsExported = $false
            providerCredentialRowsExported = $false
            applicationPasswords = 'BCrypt hashes for the documented demo accounts only'
        }
    }
    $manifestPath = Join-Path $outputDirectory 'manifest.json'
    $manifestJson = $manifest | ConvertTo-Json -Depth 6
    [IO.File]::WriteAllText($manifestPath, $manifestJson + [Environment]::NewLine, $utf8NoBom)

    Write-Host "[DONE] H2 business snapshot: $resolvedOutput" -ForegroundColor Green
    Write-Host "[CHECK] $($rowCounts.Count) tables, $($manifest.totalRows) rows, SHA-256 $hash"
}
finally {
    if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) {
        Remove-Item -LiteralPath $temporaryPath -Force
    }
}
