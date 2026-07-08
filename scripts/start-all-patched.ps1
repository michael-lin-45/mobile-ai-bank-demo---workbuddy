<#
.SYNOPSIS
一键启动: Redis(6379) + Collector(4318) + Backend(9090) + Core(8080) + Frontend(3000)
基于外部 M2 参考脚本重构，启动顺序: Redis → Collector → Backend → Core → Frontend

端口规划（本地实际端口）:
  Redis     = 6379
  Collector = 4318 (OTLP HTTP) + 8888 (Prometheus exporter)
  Backend   = 9090 (Spring Boot, H2)
  Core      = 8080 (Spring Boot, OTel Agent 注入)
  Frontend  = 3000 (Vite+React)
#>

$ErrorActionPreference = "SilentlyContinue"
$host.ui.RawUI.WindowTitle = "AI Bank 可观测系统启动器"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# ======================================
# 通用工具函数
# ======================================

function Stop-PortOccupier {
  param([int]$Port, [int]$RetryCount = 3)

  # 1. Quick check: can we bind the port? (fast, never hangs)
  $listener = $null
  try {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
    $listener.Start()
    $listener.Stop()
    return $true   # Port is free
  } catch {}
  finally { if ($listener) { try { $listener.Stop() } catch {} } }

  # 2. Port is in use - find the owning PID
  #    Use .NET GetActiveTcpListeners + process scan (avoids Get-NetTCPConnection hang)
  for ($i = 0; $i -le $RetryCount; $i++) {
    $conns = $null
    try {
      # GetActiveTcpListeners is fast and never hangs
      $conns = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
    } catch { Start-Sleep 1; continue }

    # Check if our port is in the active listener list
    $isListening = $false
    foreach ($ep in $conns) {
      if ($ep.Port -eq $Port) { $isListening = $true; break }
    }

    if (-not $isListening) {
      # Port not in LISTEN state (maybe TIME_WAIT) - wait and retry
      Start-Sleep 1
      continue
    }

    # 3. Find PID owning the port via Get-NetTCPConnection (with fallback to netstat)
    #    Try Get-NetTCPConnection first, but with a hard timeout via [System.Diagnostics.Process]
    $pids = @()
    try {
      $c = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
      if ($c) {
        $pids = @($c | Where-Object { $_.OwningProcess -gt 0 } | Select-Object -ExpandProperty OwningProcess -Unique)
      }
    } catch {}

    # Fallback: use netstat -ano if Get-NetTCPConnection returned nothing
    if ($pids.Count -eq 0) {
      try {
        $ns = & cmd /c "netstat -ano -p TCP" 2>$null
        foreach ($line in $ns) {
          if ($line -match ":$Port\s.*LISTENING\s+(\d+)$") {
            $pids += [int]$Matches[1]
          }
        }
      } catch {}
    }

    if ($pids.Count -eq 0) { Start-Sleep 1; continue }

    # 4. Kill each process
    foreach ($pidVal in $pids) {
      $proc = Get-Process -Id $pidVal -ErrorAction SilentlyContinue
      if (-not $proc) { continue }
      try {
        Stop-Process -Id $pidVal -Force -ErrorAction Stop
        Write-Host ("  Stopped PID " + $pidVal + " (" + $proc.ProcessName + ") on port " + $Port) -ForegroundColor DarkGray
      } catch {
        $errMsg = $_.Exception.Message
        Write-Warning ("  Failed to stop PID " + $pidVal + " on port " + $Port + ": " + $errMsg)
      }
    }

    Start-Sleep 1

    # Re-check with .NET after killing
    $listener2 = $null
    try {
      $listener2 = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
      $listener2.Start()
      $listener2.Stop()
      return $true   # Port is now free
    } catch {}
    finally { if ($listener2) { try { $listener2.Stop() } catch {} } }
  }

  # Final check
  $finalListener = $null
  try {
    $finalListener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
    $finalListener.Start()
    $finalListener.Stop()
    return $true
  } catch {
    Write-Warning ("  Port " + $Port + " still occupied after " + $RetryCount + " retries")
    return $false
  } finally { if ($finalListener) { try { $finalListener.Stop() } catch {} } }
}

    # 2. Find and kill the process owning the port
    $conns = $null
    try {
      $conns = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    } catch {
      Start-Sleep 1
      continue
    }

    if (-not $conns) {
      Start-Sleep 1
      continue
    }

    # 3. Collect unique PIDs (Get-NetTCPConnection may return multiple rows)
    $pids = @($conns | Where-Object { $_.OwningProcess -gt 0 } | Select-Object -ExpandProperty OwningProcess -Unique)
    if ($pids.Count -eq 0) {
      Start-Sleep 1
      continue
    }

    # 4. Kill each process
    foreach ($pidVal in $pids) {
      $proc = Get-Process -Id $pidVal -ErrorAction SilentlyContinue
      if (-not $proc) { continue }
      try {
        Stop-Process -Id $pidVal -Force -ErrorAction Stop
        Write-Host ('  Stopped PID ' + $pidVal + ' (' + $proc.ProcessName + ') on port ' + $Port) -ForegroundColor DarkGray
      } catch {
        $errMsg = $_.Exception.Message
        Write-Warning ('  Failed to stop PID ' + $pidVal + ' on port ' + $Port + ': ' + $errMsg)
      }
    }

    Start-Sleep 1
  }

  # Final check with .NET
  $finalListener = $null
  try {
    $finalListener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
    $finalListener.Start()
    $finalListener.Stop()
    return $true
  } catch {
    Write-Warning ('  Port ' + $Port + ' still occupied after ' + $RetryCount + ' retries')
    return $false
  } finally {
    if ($finalListener) { $finalListener.Stop() }
  }
}

function Wait-Url {
  param([string]$Url, [int]$MaxTries = 30, [int]$SleepSec = 2)
  for ($i = 0; $i -lt $MaxTries; $i++) {
    try {
      Invoke-WebRequest $Url -UseBasicParsing -TimeoutSec 2 | Out-Null
      return $true
    } catch {
      Write-Host "  等待 $Url ... ($i/$MaxTries)"
      Start-Sleep $SleepSec
    }
  }
  return $false
}

# 启动失败时，停止全部已启动服务后退出
function Stop-AllAndExit {
  Write-Host "`n=== 清理已启动服务 ===" -ForegroundColor Yellow
  Get-Process -Name otelcol -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
  $ports = @(6379, 9090, 4318, 8888, 8080, 3000)
  $labels = @{6379='Redis'; 9090='Backend'; 4318='Collector'; 8888='Collector-Prometheus'; 8080='Core'; 3000='Frontend'}
  foreach ($p in $ports) {
    Get-NetTCPConnection -LocalPort $p -ErrorAction SilentlyContinue | ForEach-Object {
      Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue
    }
    Write-Host "  已停止 $($labels[$p]):$p" -ForegroundColor Yellow
  }
  pause
  exit 1
}

# ======================================
# 配置部分
# ======================================

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ROOT = (Get-Item "$ScriptDir\..").FullName

$REDIS_PORT       = 6379
$COLLECTOR_PORT   = 4318
$COLLECTOR_PROM   = 8887
$OBS_PORT         = 9090
$CORE_PORT        = 8080
$FRONTEND_PORT    = 3000

$CURRENT_PATH     = Get-Location
$OBS_BACKEND_PATH = Join-Path $ROOT "observability\backend"
$OBS_FRONTEND_PATH = Join-Path $ROOT "observability\frontend"
$COLLECTOR_PATH   = Join-Path $ROOT "observability\otel-collector"
$CORE_PROJECT_PATH = $ROOT
$AGENT_PATH       = Join-Path $ROOT "opentelemetry-javaagent.jar"
$REDIS_BIN        = "C:\Program Files\Redis\redis-server.exe"
$REDIS_CLI        = "C:\Program Files\Redis\redis-cli.exe"

# JVM memory limits (prevent OOM kill)
$OBS_JVM_MEM      = "-Xms512m -Xmx1024m -XX:MaxMetaspaceSize=256m"
$CORE_JVM_MEM     = "-Xms512m -Xmx1024m -XX:MaxMetaspaceSize=256m"
$OBS_JAR          = "observability-backend-0.1.0-SNAPSHOT.jar"
$CORE_JAR         = "mobile-ai-demo-0.0.1-SNAPSHOT.jar"

# ======================================
# 步骤0：环境检查
# ======================================

Write-Host "`n=== 0/6 环境检查 ===" -ForegroundColor Green
Write-Host "  ROOT: $ROOT" -ForegroundColor DarkGray
if (-not (Test-Path $REDIS_BIN)) { Write-Error "Redis 缺失: $REDIS_BIN"; pause; exit 1 }
if (-not (Test-Path $REDIS_CLI)) { Write-Error "redis-cli 缺失: $REDIS_CLI"; pause; exit 1 }
if (-not (Test-Path $AGENT_PATH)) { Write-Warning "  OTel Agent 缺失, 全链路将无法上报" }
Write-Host "端口: Redis $REDIS_PORT / Collector $COLLECTOR_PORT(+$COLLECTOR_PROM) / Backend $OBS_PORT / Core $CORE_PORT / Frontend $FRONTEND_PORT"

# ======================================
# 步骤1：Redis 热层
# ======================================

Write-Host "`n=== 1/6 启动 Redis ($REDIS_PORT) ===" -ForegroundColor Green

# Always stop and restart (ensures clean state)
Stop-PortOccupier -Port $REDIS_PORT | Out-Null
Start-Process $REDIS_BIN -WindowStyle Hidden -ErrorAction SilentlyContinue
$redisUp = $false
for ($i = 0; $i -lt 15; $i++) {
  try { & $REDIS_CLI PING 2>$null | Out-Null; $redisUp = $true; Write-Host "  Redis PONG (${i}s)" -ForegroundColor Green; break }
  catch { Write-Host "  ... ($i/15)"; Start-Sleep 1 }
}
if (-not $redisUp) {
  Write-Warning "  Redis 启动超时"
}

# ======================================
# 步骤2：OTel Collector (先于 Backend, Collector exporter 是懒连接)
# ======================================

Write-Host "`n=== 2/6 启动 Collector ($COLLECTOR_PORT) ===" -ForegroundColor Green

# 清理旧 otelcol 进程：按进程名杀（避免按端口杀的顺序竞态）
Get-Process -Name otelcol -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
# 等待端口释放（OS 释放端口有延迟）
for ($pw = 0; $pw -lt 10; $pw++) {
  $c4 = Get-NetTCPConnection -LocalPort $COLLECTOR_PORT -ErrorAction SilentlyContinue
  $c8 = Get-NetTCPConnection -LocalPort $COLLECTOR_PROM -ErrorAction SilentlyContinue
  if (-not $c4 -and -not $c8) { Write-Host "  端口 $COLLECTOR_PORT / $COLLECTOR_PROM 已释放 (${pw}s)" -ForegroundColor DarkGray; break }
  Start-Sleep 1
}

$collectorExe = Join-Path $COLLECTOR_PATH "otelcol.exe"
$collectorCfg = Join-Path $COLLECTOR_PATH "config.yaml"

if (Test-Path $collectorExe) {
  # 写临时 .cmd 批处理文件（避免 Start-Process 直接传参 exe 时空格路径截断）
  $collectorBat = "$env:TEMP\start-collector.cmd"
  @"
@echo off
cd /d "$COLLECTOR_PATH"
"$collectorExe" --config "$collectorCfg" > "$COLLECTOR_PATH\collector.log" 2>&1
"@ | Out-File -FilePath $collectorBat -Encoding ASCII
  Start-Process $collectorBat -WindowStyle Hidden -PassThru

  # Collector 的 OTLP endpoint 只接受 OTLP 协议, HTTP GET 返回 404, 所以用端口检测而非 URL
  $collUp = $false
  for ($ci = 0; $ci -lt 20; $ci++) {
    try { Get-NetTCPConnection -LocalPort $COLLECTOR_PORT -EA Stop | Out-Null; $collUp = $true; break }
    catch { Write-Host "  等待 Collector:$COLLECTOR_PORT ... ($ci/20)"; Start-Sleep 2 }
  }
  if ($collUp) {
    Write-Host "  Collector 启动成功, 端口: $COLLECTOR_PORT" -ForegroundColor Green
  } else {
    Write-Warning "  Collector 端口未就绪, 请检查 $COLLECTOR_PATH\collector.log"
  }
} else {
  Write-Warning "  未找到 otelcol.exe, 请手动启动"
}

# ======================================
# 步骤3：可观测后端 Spring Boot
# ======================================

Write-Host "`n=== 3/6 启动可观测后端 ($OBS_PORT) ===" -ForegroundColor Green

Stop-PortOccupier -Port $OBS_PORT | Out-Null
Set-Location $OBS_BACKEND_PATH

# 编译后端 (确保最新代码生效)
Write-Host "  编译可观测后端..." -ForegroundColor DarkGray
$compileCmd = "cd '$OBS_BACKEND_PATH'; .\mvnw.cmd package -DskipTests -q"
$compileBytes = [System.Text.Encoding]::Unicode.GetBytes($compileCmd)
$compileB64 = [Convert]::ToBase64String($compileBytes)
$compileProc = Start-Process powershell -ArgumentList "-NoProfile", "-EncodedCommand", $compileB64 -WindowStyle Hidden -Wait -PassThru
if ($compileProc.ExitCode -ne 0) {
  Write-Error "  后端编译失败 (ExitCode: $($compileProc.ExitCode))，请检查 Maven 输出"
  Stop-AllAndExit
}
Write-Host "  后端编译完成" -ForegroundColor Green

# Backend JAR check
$obsJarPath = Join-Path $OBS_BACKEND_PATH "target\$OBS_JAR"
if (-not (Test-Path $obsJarPath)) {
  Write-Error "  Backend JAR not found: $obsJarPath"
  Stop-AllAndExit
}
Write-Host "  JAR ready: $OBS_JAR" -ForegroundColor DarkGray

$obsCmd = "cd '$OBS_BACKEND_PATH'; java $OBS_JVM_MEM -jar 'target/$OBS_JAR' --server.port=$OBS_PORT"
$obsBytes = [System.Text.Encoding]::Unicode.GetBytes($obsCmd)
$obsB64 = [Convert]::ToBase64String($obsBytes)
$backendProc = Start-Process powershell -ArgumentList "-NoExit", "-EncodedCommand", $obsB64 -WindowStyle Normal -PassThru
if (Wait-Url "http://127.0.0.1:$OBS_PORT/health" -MaxTries 40 -SleepSec 3) {
  Write-Host "  可观测后端启动成功, 端口: $OBS_PORT" -ForegroundColor Green
} else {
  Write-Error "  可观测后端启动失败"
  Stop-AllAndExit
}
Set-Location $ROOT

# ======================================
# 步骤4：手机银行核心系统 (带 OTel Agent + DNS)
# ======================================

Write-Host "`n=== 4/6 启动核心系统 ($CORE_PORT) ===" -ForegroundColor Green

Stop-PortOccupier -Port $CORE_PORT | Out-Null
Set-Location $CORE_PROJECT_PATH

# 动态获取系统 DNS（沙箱默认 DNS 可能不可用）
$DNS_SERVER = $null
try {
  $dnsAddrs = (Get-DnsClientServerAddress -AddressFamily IPv4 -ErrorAction Stop |
    Where-Object { $_.ServerAddresses.Count -gt 0 } |
    Select-Object -First 1).ServerAddresses
  $DNS_SERVER = $dnsAddrs[0]
  Write-Host "  系统 DNS: $DNS_SERVER" -ForegroundColor DarkGray
} catch {
  Write-Host "  DNS: 无法自动检测" -ForegroundColor Yellow
}
$dnsJvmArgs = if ($DNS_SERVER) { "-Dreactor.netty.dns.nameservers=$DNS_SERVER -Djava.net.preferIPv4Stack=true" } else { "" }

# 编译核心系统 (确保最新代码生效)
Write-Host "  编译核心系统..." -ForegroundColor DarkGray
$coreCompileCmd = "cd '$CORE_PROJECT_PATH'; .\mvnw.cmd package -DskipTests -q"
$coreCompileBytes = [System.Text.Encoding]::Unicode.GetBytes($coreCompileCmd)
$coreCompileB64 = [Convert]::ToBase64String($coreCompileBytes)
$coreCompileProc = Start-Process powershell -ArgumentList "-NoProfile", "-EncodedCommand", $coreCompileB64 -WindowStyle Hidden -Wait -PassThru
if ($coreCompileProc.ExitCode -ne 0) {
  Write-Error "  核心系统编译失败 (ExitCode: $($coreCompileProc.ExitCode))，请检查 Maven 输出"
  Stop-AllAndExit
}
Write-Host "  核心系统编译完成" -ForegroundColor Green

# JAR existence check
$coreJarPath = Join-Path $CORE_PROJECT_PATH "target\$CORE_JAR"
if (-not (Test-Path $coreJarPath)) {
  Write-Error "  Core JAR not found: $coreJarPath"
  Stop-AllAndExit
}
Write-Host "  JAR ready: $CORE_JAR" -ForegroundColor DarkGray

# 写临时 .cmd 批处理文件（避免 & / set 在 PowerShell Base64 中语法错误）
$coreBat = "$env:TEMP\start-bank-core.cmd"
@"
@echo off
cd /d "$CORE_PROJECT_PATH"
set OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:$COLLECTOR_PORT
set OTEL_SERVICE_NAME=mobile-bank-core
set OTEL_METRICS_EXPORTER=otlp
set OTEL_TRACES_EXPORTER=otlp
set OTEL_LOGS_EXPORTER=otlp
"@ | Out-File -FilePath $coreBat -Encoding ASCII
# Build direct java args (avoid JAVA_TOOL_OPTIONS double-pass)
$coreJavaArgs = "$CORE_JVM_MEM $dnsJvmArgs"
if (Test-Path $AGENT_PATH) {
  $coreJavaArgs = "$coreJavaArgs -javaagent:`"$AGENT_PATH`""
}
Add-Content -Path $coreBat -Value "java $coreJavaArgs -jar `"target\$CORE_JAR`" --server.port=$CORE_PORT"
$coreProc = Start-Process $coreBat -WindowStyle Normal -PassThru
if (Wait-Url "http://127.0.0.1:$CORE_PORT/actuator/health" -MaxTries 60 -SleepSec 3) {
  Write-Host "  核心系统启动成功, 端口: $CORE_PORT (OTel Agent 已注入)" -ForegroundColor Green
} else {
  Write-Warning "  核心系统健康检查超时, 请检查控制台日志"
}
Set-Location $ROOT

# ======================================
# 步骤5：前端 React
# ======================================

Write-Host "`n=== 5/6 启动前端 ($FRONTEND_PORT) ===" -ForegroundColor Green

Stop-PortOccupier -Port $FRONTEND_PORT | Out-Null
Set-Location $OBS_FRONTEND_PATH
if (Test-Path (Join-Path $OBS_FRONTEND_PATH "node_modules")) {
  $feProc = Start-Process cmd -ArgumentList "/c","npm run dev" -WindowStyle Hidden -PassThru
  if (Wait-Url "http://127.0.0.1:$FRONTEND_PORT/" -MaxTries 30 -SleepSec 2) {
    Write-Host "  前端启动成功, 端口: $FRONTEND_PORT" -ForegroundColor Green
  } else {
    Write-Warning "  前端启动超时"
  }
} else {
  Write-Warning "  node_modules 缺失, 请执行: cd '$OBS_FRONTEND_PATH'; npm install"
}
Set-Location $ROOT

# ======================================
# 步骤6：完成
# ======================================

Write-Host "`n=== 6/6 启动完成 ===" -ForegroundColor Green
Write-Host "  可观测平台 API : http://127.0.0.1:$OBS_PORT/api/v1/overview"
Write-Host "  核心项目接口   : http://127.0.0.1:$CORE_PORT"
Write-Host "  Collector OTLP : http://127.0.0.1:$COLLECTOR_PORT"
Write-Host "  Redis 热层     : 127.0.0.1:$REDIS_PORT"
Write-Host "  前端界面       : http://127.0.0.1:$FRONTEND_PORT"
Write-Host "`n按任意键退出启动器, 服务继续在后台运行..."
$null = $host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
