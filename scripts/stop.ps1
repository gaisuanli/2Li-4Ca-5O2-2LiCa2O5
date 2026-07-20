[CmdletBinding()]
param()

. (Join-Path $PSScriptRoot 'Common.ps1')

$root = Get-PlatformRoot
foreach ($component in @('ai', 'simulator', 'frontend', 'backend')) {
    Stop-TrackedComponent -Component $component -Root $root
}
Write-Host '[完成] 已处理全部由交付脚本跟踪的组件。' -ForegroundColor Green
