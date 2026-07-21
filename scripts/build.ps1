[CmdletBinding()]
param(
    [switch]$InstallDependencies,
    [switch]$SkipTests,
    [switch]$WithAiDependencies
)

. (Join-Path $PSScriptRoot 'Common.ps1')

$root = Get-PlatformRoot
$frontend = Assert-ProjectPath -Path (Join-Path $root 'frontend') -Root $root
$backend = Assert-ProjectPath -Path (Join-Path $root 'backend') -Root $root
$simulator = Assert-ProjectPath -Path (Join-Path $root 'device-simulator') -Root $root
$ai = Assert-ProjectPath -Path (Join-Path $root 'ai-service') -Root $root

$pnpm = Get-RequiredCommandPath -Name 'pnpm.cmd'
$maven = Get-RequiredCommandPath -Name 'mvn.cmd'
$null = Get-JavaRunner
$node = Get-RequiredCommandPath -Name 'node.exe'
Assert-SupportedNodeVersion -NodePath $node

if ($InstallDependencies) {
    Invoke-CheckedCommand -FilePath $pnpm -Arguments @('install', '--frozen-lockfile') -WorkingDirectory $root -Label '安装锁定的 JavaScript 工作区依赖'
}
else {
    if (-not (Test-Path -LiteralPath (Join-Path $frontend 'node_modules\vite\bin\vite.js') -PathType Leaf)) {
        throw 'PC 前端依赖不存在。请使用 -InstallDependencies 重新执行构建。'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $simulator 'node_modules\express\package.json') -PathType Leaf)) {
        throw '设备模拟器依赖不存在。请使用 -InstallDependencies 重新执行构建。'
    }
}

if ($WithAiDependencies) {
    $systemPython = Get-PythonRunner -Root (Join-Path $root '__system_python_only__')
    $venvDirectory = Assert-ProjectPath -Path (Join-Path $ai '.venv') -Root $root
    if (-not (Test-Path -LiteralPath $venvDirectory -PathType Container)) {
        Invoke-CheckedCommand -FilePath $systemPython.FilePath `
            -Arguments @($systemPython.PrefixArguments + @('-m', 'venv', $venvDirectory)) `
            -WorkingDirectory $ai -Label '创建 AI Python 虚拟环境'
    }
    $aiPython = Get-PythonRunner -Root $root
    Invoke-CheckedCommand -FilePath $aiPython.FilePath `
        -Arguments @($aiPython.PrefixArguments + @('-m', 'pip', 'install', '-r', (Join-Path $ai 'requirements.txt'))) `
        -WorkingDirectory $ai -Label '安装可选 AI 模型依赖'
}

if (-not $SkipTests) {
    Invoke-CheckedCommand -FilePath $pnpm -Arguments @('run', 'test') -WorkingDirectory $frontend -Label '运行 PC 前端单元测试'
    Invoke-CheckedCommand -FilePath $pnpm -Arguments @('run', 'test') -WorkingDirectory $simulator -Label '运行设备模拟器协议测试'
    $python = Get-PythonRunner -Root $root
    Invoke-CheckedCommand -FilePath $python.FilePath `
        -Arguments @($python.PrefixArguments + @('-m', 'unittest', 'discover', '-s', 'tests', '-p', 'test_*.py')) `
        -WorkingDirectory $ai -Label '运行 AI 适配器安全模式测试'
}

Invoke-CheckedCommand -FilePath $pnpm -Arguments @('run', 'build') -WorkingDirectory $frontend -Label '构建 PC 前端'
$mavenArguments = @('-q', 'package')
if ($SkipTests) {
    $mavenArguments = @('-q', '-DskipTests', 'package')
}
Invoke-CheckedCommand -FilePath $maven -Arguments $mavenArguments -WorkingDirectory $backend -Label '测试并打包 Spring Boot 后端'

$frontendArtifact = Join-Path $frontend 'dist\index.html'
$backendArtifact = Get-ChildItem -LiteralPath (Join-Path $backend 'target') -Filter 'building-safety-api-*.jar' -File |
    Where-Object { $_.Name -notlike '*.original' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not (Test-Path -LiteralPath $frontendArtifact -PathType Leaf)) {
    throw 'PC 前端构建完成但未找到 frontend\dist\index.html。'
}
if ($null -eq $backendArtifact) {
    throw '后端构建完成但未找到可执行 JAR。'
}

Write-Host '[完成] 全部构建产物已生成。' -ForegroundColor Green
Write-Host "PC 前端：$frontendArtifact"
Write-Host "后端 JAR：$($backendArtifact.FullName)"
