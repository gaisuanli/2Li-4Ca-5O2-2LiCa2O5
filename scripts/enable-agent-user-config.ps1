[CmdletBinding()]
param(
    [string]$EnvFile = '.env'
)

. (Join-Path $PSScriptRoot 'Common.ps1')

$root = Get-PlatformRoot
$resolvedEnv = Assert-ProjectPath -Path (Join-Path $root $EnvFile) -Root $root
if (-not (Test-Path -LiteralPath $resolvedEnv -PathType Leaf)) {
    $example = Assert-ProjectPath -Path (Join-Path $root '.env.example') -Root $root
    Copy-Item -LiteralPath $example -Destination $resolvedEnv
}

$lines = [Collections.Generic.List[string]]::new()
foreach ($line in @(Get-Content -LiteralPath $resolvedEnv -Encoding UTF8)) {
    $lines.Add($line)
}

$keyName = 'AI_AGENT_CREDENTIAL_ENCRYPTION_KEY'
$enabledName = 'AI_AGENT_USER_CONFIG_ENABLED'
$keyIndex = -1
$enabledIndex = -1
for ($index = 0; $index -lt $lines.Count; $index++) {
    if ($lines[$index] -match '^AI_AGENT_CREDENTIAL_ENCRYPTION_KEY=(.*)$') { $keyIndex = $index }
    if ($lines[$index] -match '^AI_AGENT_USER_CONFIG_ENABLED=(.*)$') { $enabledIndex = $index }
}

if ($keyIndex -ge 0 -and $lines[$keyIndex] -match '^AI_AGENT_CREDENTIAL_ENCRYPTION_KEY=(.+)$') {
    Write-Host '[KEEP] .env already contains a credential encryption key; it was not rotated.' -ForegroundColor DarkGray
}
else {
    $bytes = New-Object byte[] 32
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    try {
        $encoded = [Convert]::ToBase64String($bytes)
        $entry = "$keyName=$encoded"
        if ($keyIndex -ge 0) { $lines[$keyIndex] = $entry } else { $lines.Add($entry) }
    }
    finally {
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
    Write-Host '[CREATED] Wrote a random 256-bit credential encryption key to the Git-ignored .env.' -ForegroundColor Green
}

if ($enabledIndex -ge 0) {
    $lines[$enabledIndex] = "$enabledName=true"
}
else {
    $lines.Add("$enabledName=true")
}

Set-Content -LiteralPath $resolvedEnv -Encoding UTF8 -Value $lines
Write-Host '[SAFE] The master key was not printed. Restart the backend to apply it.'
