<#
.SYNOPSIS
停止所有服务: Redis + Backend(9090) + Collector(4318) + Core(8080) + Frontend(3000)
#>
$ports = @(6379, 9090, 4318, 8080, 3000)
$labels = @{6379='Redis'; 9090='Backend'; 4318='Collector'; 8080='Core'; 3000='Frontend'}

foreach ($port in $ports) {
    $conns = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue
    if ($conns) {
        foreach ($c in $conns) {
            Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue
        }
        Write-Host "Stopped $($labels[$port]):$port" -ForegroundColor Yellow
    } else {
        Write-Host "Port ${port}: already free" -ForegroundColor DarkGray
    }
}
Write-Host "All services stopped." -ForegroundColor Green
