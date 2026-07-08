<#
.SYNOPSIS
一键运行全部自动化测试: API 测试 + Playwright 前端测试

用法:
  .\test\run_all.ps1              # 运行全部
  .\test\run_all.ps1 -ApiOnly     # 仅 API 测试
  .\test\run_all.ps1 -WebOnly     # 仅前端测试

前提:
  - 所有服务已启动 (.\scripts\start-all.ps1)
  - Python 3.13 可用
  - Node.js + Playwright 已安装
#>
param(
    [switch]$ApiOnly,
    [switch]$WebOnly
)

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ROOT = (Get-Item "$ScriptDir\..").FullName
$LOGDIR = "$ScriptDir\logs"
if (!(Test-Path $LOGDIR)) { New-Item -ItemType Directory -Path $LOGDIR -Force | Out-Null }

$PYTHON = "$env:USERPROFILE\.workbuddy\binaries\python\versions\3.13.12\python.exe"
$NPM = "$env:USERPROFILE\.workbuddy\binaries\node\versions\22.22.2\npm.cmd"

$timestamp = Get-Date -Format "yyyy-MM-dd-HHmmss"
$apiLog = "$LOGDIR\test_api_$timestamp.log"
$webLog = "$LOGDIR\test_web_$timestamp.log"

$allPassed = $true

# ── API 测试 ──
if (-not $WebOnly) {
    Write-Host "`n=== API 全量测试 ===" -ForegroundColor Cyan
    Write-Host "  脚本: test\test_api_full.py" -ForegroundColor DarkGray
    Write-Host "  日志: $apiLog" -ForegroundColor DarkGray
    
    $exitCode = 0
    & $PYTHON -u "$ROOT\test\test_api_full.py" 2>&1 | Tee-Object $apiLog
    $exitCode = $LASTEXITCODE
    
    if ($exitCode -eq 0) {
        Write-Host "  API 测试: PASS" -ForegroundColor Green
    } else {
        Write-Host "  API 测试: FAIL (exit=$exitCode)" -ForegroundColor Red
        $allPassed = $false
    }
}

# ── Playwright 前端测试 ──
if (-not $ApiOnly) {
    Write-Host "`n=== Playwright 前端测试 ===" -ForegroundColor Cyan
    Write-Host "  日志: $webLog" -ForegroundColor DarkGray
    
    Set-Location $ROOT
    $exitCode = 0
    & $NPM exec playwright test test/test_frontend.spec.js --reporter=list 2>&1 | Tee-Object $webLog
    $exitCode = $LASTEXITCODE
    
    if ($exitCode -eq 0) {
        Write-Host "  前端测试: PASS" -ForegroundColor Green
    } else {
        Write-Host "  前端测试: FAIL (exit=$exitCode)" -ForegroundColor Red
        $allPassed = $false
    }
}

# ── Summary ──
Write-Host "`n================================="
if ($allPassed) {
    Write-Host " ALL TESTS PASSED" -ForegroundColor Green
} else {
    Write-Host " SOME TESTS FAILED — check logs" -ForegroundColor Red
}
Write-Host " Logs: $LOGDIR" -ForegroundColor DarkGray
Write-Host "================================="
exit $(if ($allPassed) { 0 } else { 1 })
