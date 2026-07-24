<#
.SYNOPSIS
停止所有服务: Redis(6379) + Collector(4318 OTLP / 8887 Prometheus) + Backend(9090) + Core(8080) + Frontend(3000)
独立脚本: 不依赖 start-all 可直接运行清理全部后台服务
#>
$ErrorActionPreference = "SilentlyContinue"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# 前端目录绝对路径（用于兜底匹配 vite 进程命令行）
try {
    $FrontendPath = (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) "..\observability\frontend" | Resolve-Path).Path
} catch {
    $FrontendPath = $null
}

# 1) 兜底：先按进程名杀 Collector（它不一定监听 4318/8887，端口未监听时按名杀才可确保不漏杀）
Get-Process -Name otelcol-contrib -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

# 2) 按端口杀：仅匹配 LISTEN 状态的连接，去重 OwningProcess 后逐个强制结束
$ports  = @(6379, 4318, 8887, 8080, 9090, 3000)
$labels = @{
    6379 = 'Redis'
    4318 = 'Collector-OTLP'
    8887 = 'Collector-Prometheus'
    8080 = 'Core'
    9090 = 'Backend'
    3000 = 'Frontend'
}

foreach ($port in $ports) {
    # -State Listen 只取监听中的连接，避免把 TIME_WAIT / ESTABLISHED 的客户端连接误算进来
    $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($conns) {
        # 同一端口可能有多个连接指向同一进程，去重 OwningProcess
        $pids = @($conns | ForEach-Object { $_.OwningProcess } | Sort-Object -Unique)
        foreach ($pidVal in $pids) {
            Stop-Process -Id $pidVal -Force -ErrorAction SilentlyContinue
        }
        Write-Host "Stopped $($labels[$port]):$port" -ForegroundColor Yellow
    } else {
        Write-Host "Port ${port}: already free" -ForegroundColor DarkGray
    }
}

# 3) 前端进程树兜底：前端以 cmd /c npm run dev 启动 (cmd -> npm -> node/vite)，
#    仅按 3000 端口杀 node 可能残留父进程 cmd/npm。此处按 node.exe 命令行含 vite / 前端路径清理整棵残留树。
if ($FrontendPath) {
    $nodeProcs = Get-CimInstance Win32_Process -Filter "Name='node.exe'" -ErrorAction SilentlyContinue
    foreach ($np in $nodeProcs) {
        $cmdLine = $np.CommandLine
        if ($cmdLine -and (($cmdLine -like "*vite*") -or ($cmdLine -like "*$FrontendPath*"))) {
            Stop-Process -Id $np.ProcessId -Force -ErrorAction SilentlyContinue
        }
    }
}

Write-Host "All services stopped." -ForegroundColor Green
