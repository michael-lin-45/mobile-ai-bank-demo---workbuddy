<#
.SYNOPSIS
一键启动: Redis(6379) + Collector(4318) + Backend(9090) + Core(8080) + Frontend(3000)
基于外部 M2 参考脚本重构，启动顺序: Redis → Collector → Backend → Core → Frontend

端口规划（本地实际端口）:
  Redis     = 6379
  Collector = 4318 (OTLP HTTP) + 8887 (Prometheus exporter)
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

function global:Stop-PortOccupier {
  param([int]$Port, [int]$RetryCount = 3)
  for ($i = 0; $i -le $RetryCount; $i++) {

    # 1. Fast port check via .NET TcpListener (never hangs, unlike Get-NetTCPConnection)
    $listener = $null
    try {
      $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
      $listener.Start()
      $listener.Stop()
      return $true
    } catch {
      # Port is in use - fall through to find and kill the process
    } finally {
      if ($listener) { try { $listener.Stop() } catch {} }
    }

    # 2. Get active TCP listeners via .NET (fast, no hang) to find the owning PID
    #    IPGlobalProperties.GetActiveTcpListeners returns only ports in LISTEN state
    $listeners = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
    $isListening = $false
    foreach ($ep in $listeners) {
      if ($ep.Port -eq $Port) { $isListening = $true; break }
    }
    if (-not $isListening) {
      # Port not in LISTEN state - might be TIME_WAIT, wait and retry
      Start-Sleep 1
      continue
    }

    # 3. Find owning PID via netstat -ano (more reliable than Get-NetTCPConnection on Windows)
    #    Filter for LISTENING lines matching our port
    $netstatOutput = ""
    try {
      $netstatOutput = (& netstat -ano -p tcp | Out-String)
    } catch {
      Start-Sleep 1
      continue
    }

    $owningPids = @{}
    foreach ($line in ($netstatOutput -split "`n")) {
      $line = $line.Trim()
      # Match lines like: TCP    0.0.0.0:9090    0.0.0.0:0    LISTENING    12345
      if ($line -match "LISTENING\s+(\d+)$") {
        $portPart = ($line -split "\s+")[1]
        if ($portPart -match ":(\d+)$") {
          $linePort = [int]$Matches[1]
          if ($linePort -eq $Port) {
            $pidMatch = [int]($line -split "\s+" | Select-Object -Last 1)
            $owningPids[$pidMatch] = $true
          }
        }
      }
    }

    if ($owningPids.Count -eq 0) {
      Start-Sleep 1
      continue
    }

    # 4. Kill each owning process
    foreach ($pidVal in $owningPids.Keys) {
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
    if ($finalListener) { try { $finalListener.Stop() } catch {} }
  }
}

function Wait-Url {
  param([string]$Url, [int]$MaxTries = 30, [int]$SleepSec = 2)
  for ($i = 0; $i -lt $MaxTries; $i++) {
    try {
      $resp = Invoke-WebRequest $Url -UseBasicParsing -TimeoutSec 5
      if ($resp.StatusCode -lt 500) { return $true }
    } catch {
      if ($i % 5 -eq 0) { Write-Host "  Waiting $Url ... ($i/$MaxTries)" }
    }
    Start-Sleep $SleepSec
  }
  return $false
}

# 实际清理逻辑（幂等）：按进程名 + 端口杀掉所有已启动服务
# 定义为 global 函数，便于 Ctrl+C 事件处理器在子作用域中直接调用
function global:Stop-AllServices {
  if ($global:CleanedUp) { return }
  $global:CleanedUp = $true
  Write-Host "`n=== 正在清理已启动的服务 (Ctrl+C / 失败退出) ===" -ForegroundColor Yellow
  # Kill otelcol by process name (它不监听单一可预测端口)
  Get-Process -Name otelcol-contrib -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
  # Kill all known service ports using the robust Stop-PortOccupier
  $ports = @(6379, 9090, 4318, 8887, 8080, 3000)
  $labels = @{6379='Redis'; 9090='Backend'; 4318='Collector'; 8887='Collector-Prometheus'; 8080='Core'; 3000='Frontend'}
  foreach ($p in $ports) {
    $null = global:Stop-PortOccupier -Port $p
    Write-Host "  Cleaned $($labels[$p]):$p" -ForegroundColor Yellow
  }
  Write-Host "=== 清理完成 ===" -ForegroundColor Green
}

# 启动失败时，停止全部已启动服务后退出
function Stop-AllAndExit {
  global:Stop-AllServices
  Write-Error "启动失败，已停止本次启动过程中已启动的服务。详情见上方日志。"
  Start-Sleep -Seconds 5
  exit 1
}

# ECJ 残片检测：IDE(ECJ) 在 classpath 缺包时仍会生成"带错误的 class"，
# 该 class 含 "Unresolved compilation" 字符串常量，会被 maven 增量编译当成
# "已编译"而跳过，最终打进 jar，运行时才报 cannot be resolved。
# 此函数扫描 target/classes 下的 .class 是否含该标记，发现即中止启动。
function global:Assert-NoEcjErrorClasses {
  param([string]$ProjectPath, [string]$Label)
  $classesDir = Join-Path $ProjectPath "target\classes"
  if (-not (Test-Path $classesDir)) { return }   # 尚未编译则跳过
  $hit = Get-ChildItem -Recurse -Path $classesDir -Filter *.class | Where-Object {
    try { Select-String -Path $_.FullName -Pattern "Unresolved compilation" -Quiet -ErrorAction SilentlyContinue } catch { $false }
  } | Select-Object -First 1
  if ($hit) {
    Write-Warning "[$Label] 检测到 ECJ 错误 class 残片: $($hit.FullName)`n  请清理 IDE 编译产物后重跑: 删除 target/ 或执行 'mvnw.cmd clean'。"
    Stop-AllAndExit
  }
}

# ======================================
# Ctrl+C 处置：仅做提示，不杀服务（服务在后台独立运行）
# ======================================

$global:CtrlC     = $false
$global:CleanedUp = $false

# 注册 Ctrl+C 处理：仅置标志位 + 提示，绝不杀服务。
# 所有服务均以 Start-Process -WindowStyle Hidden 脱离启动器独立运行，
# 启动器退出（含 Ctrl+C、被任务管理器/工具杀掉）都不会停止它们。
$null = Register-ObjectEvent -InputObject ([Console]) -EventName CancelKeyPress -Action {
  $global:CtrlC = $true
  Write-Host "`n[启动器] 收到 Ctrl+C。所有服务继续在后台独立运行（已脱离本启动器），停止请另开终端运行 scripts/stop-all.ps1。" -ForegroundColor Cyan
}

# 包裹整段启动流程：Ctrl+C / 异常退出时，finally 中执行清理
try {


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
# 注意: -Duser.timezone 的值含 "/", 在 Base64 子 PowerShell 二次解析时会被在 "-Duser" 处拆断
# (java 会把 ".timezone=Asia/Shanghai" 当主类 → ClassNotFoundException)。
# 必须用字面双引号包成单 token; 故这两个变量用单引号定义以保留内部双引号。
$OBS_JVM_MEM      = '-Xms512m -Xmx1024m -XX:MaxMetaspaceSize=256m "-Duser.timezone=Asia/Shanghai"'
$CORE_JVM_MEM     = '-Xms512m -Xmx1024m -XX:MaxMetaspaceSize=256m "-Duser.timezone=Asia/Shanghai"'
$OBS_JAR          = "observability-backend-0.1.0-SNAPSHOT.jar"
$CORE_JAR         = "mobile-ai-demo-0.0.1-SNAPSHOT.jar"

# ======================================
# 步骤0：环境检查
# ======================================

Write-Host "`n=== 0/6 环境检查 ===" -ForegroundColor Green
Write-Host "  ROOT: $ROOT" -ForegroundColor DarkGray
if (-not (Test-Path $REDIS_BIN)) { Write-Error "Redis 缺失: $REDIS_BIN"; Start-Sleep -Seconds 5; exit 1 }
if (-not (Test-Path $REDIS_CLI)) { Write-Error "redis-cli 缺失: $REDIS_CLI"; Start-Sleep -Seconds 5; exit 1 }
if (-not (Test-Path $AGENT_PATH)) { Write-Warning "  OTel Agent 缺失, 全链路将无法上报" }
Write-Host "端口: Redis $REDIS_PORT / Collector $COLLECTOR_PORT(+$COLLECTOR_PROM) / Backend $OBS_PORT / Core $CORE_PORT / Frontend $FRONTEND_PORT"

# ======================================
# 步骤1：Redis 热层
# ======================================

Write-Host "`n=== 1/6 启动 Redis ($REDIS_PORT) ===" -ForegroundColor Green

# Always stop and restart (ensures clean state)
$redisStopped = Stop-PortOccupier -Port $REDIS_PORT
if (-not $redisStopped) {
  Write-Warning "  端口 $REDIS_PORT 仍被占用，无法释放"
  Stop-AllAndExit
}
# Redis 日志重定向到文件 (便于排障, 默认 Redis 日志走 stdout)
$redisLog = Join-Path $ROOT "redis.log"
Start-Process $REDIS_BIN -WindowStyle Hidden -ErrorAction SilentlyContinue -RedirectStandardOutput $redisLog
$redisUp = $false
for ($i = 0; $i -lt 15; $i++) {
  try { & $REDIS_CLI PING 2>$null | Out-Null; $redisUp = $true; Write-Host "  Redis PONG (${i}s)" -ForegroundColor Green; break }
  catch { Write-Host "  ... ($i/15)"; Start-Sleep 1 }
}
if (-not $redisUp) {
  Write-Warning "  Redis 启动超时"
  Stop-AllAndExit
}

# ======================================
# 步骤2：OTel Collector (先于 Backend, Collector exporter 是懒连接)
# ======================================

Write-Host "`n=== 2/6 启动 Collector ($COLLECTOR_PORT) ===" -ForegroundColor Green

# 清理旧 otelcol 进程：按进程名杀（避免按端口杀的顺序竞态）
Get-Process -Name otelcol-contrib -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
# 等待端口释放（OS 释放端口有延迟）
for ($pw = 0; $pw -lt 10; $pw++) {
  $c4 = Get-NetTCPConnection -LocalPort $COLLECTOR_PORT -ErrorAction SilentlyContinue
  $c8 = Get-NetTCPConnection -LocalPort $COLLECTOR_PROM -ErrorAction SilentlyContinue
  if (-not $c4 -and -not $c8) { Write-Host "  端口 $COLLECTOR_PORT / $COLLECTOR_PROM 已释放 (${pw}s)" -ForegroundColor DarkGray; break }
  Start-Sleep 1
}

$collectorExe = Join-Path $COLLECTOR_PATH "otelcol-contrib.exe"
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
  Stop-AllAndExit
  }
} else {
  Write-Warning "  未找到 otelcol-contrib.exe, 请手动启动"
  Stop-AllAndExit
}

# ======================================
# 步骤3：可观测后端 Spring Boot
# ======================================

Write-Host "`n=== 3/6 启动可观测后端 ($OBS_PORT) ===" -ForegroundColor Green

$obsStopped = Stop-PortOccupier -Port $OBS_PORT
if (-not $obsStopped) {
  Write-Warning "  端口 $OBS_PORT 仍被占用，无法释放"
  Stop-AllAndExit
}
Set-Location $OBS_BACKEND_PATH

# 编译后端 (确保最新代码生效)
Write-Host "  编译可观测后端..." -ForegroundColor DarkGray
$compileCmd = "cd '$OBS_BACKEND_PATH'; .\mvnw.cmd clean package `"-Dmaven.test.skip=true`" -q *> '$OBS_BACKEND_PATH\compile.log'"
$compileBytes = [System.Text.Encoding]::Unicode.GetBytes($compileCmd)
$compileB64 = [Convert]::ToBase64String($compileBytes)
$compileProc = Start-Process powershell -ArgumentList "-NoProfile", "-EncodedCommand", $compileB64 -WindowStyle Hidden -Wait -PassThru
if ($compileProc.ExitCode -ne 0) {
  Write-Error "  后端编译失败 (ExitCode: $($compileProc.ExitCode))，请检查 Maven 输出"
  if (Test-Path "$OBS_BACKEND_PATH\compile.log") {
    Write-Host "  ----- compile.log 尾部 -----" -ForegroundColor Yellow
    Get-Content "$OBS_BACKEND_PATH\compile.log" -Tail 50 | ForEach-Object { Write-Host "  $_" }
  }
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
Assert-NoEcjErrorClasses -ProjectPath $OBS_BACKEND_PATH -Label "Backend"

$obsCmd = "cd '$OBS_BACKEND_PATH'; java $OBS_JVM_MEM -jar target/$OBS_JAR --server.port=$OBS_PORT > '$OBS_BACKEND_PATH\backend.log' 2>&1"
$obsBytes = [System.Text.Encoding]::Unicode.GetBytes($obsCmd)
$obsB64 = [Convert]::ToBase64String($obsBytes)
$backendProc = Start-Process powershell -ArgumentList "-NoProfile", "-EncodedCommand", $obsB64 -WindowStyle Hidden -PassThru
if (Wait-Url "http://127.0.0.1:$OBS_PORT/health" -MaxTries 60 -SleepSec 3) {
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

$coreStopped = Stop-PortOccupier -Port $CORE_PORT
if (-not $coreStopped) {
  Write-Warning "  端口 $CORE_PORT 仍被占用，无法释放"
  Stop-AllAndExit
}
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
$coreCompileCmd = "cd '$CORE_PROJECT_PATH'; .\mvnw.cmd clean package `"-DskipTests`" -q *> '$CORE_PROJECT_PATH\compile.log'"
$coreCompileBytes = [System.Text.Encoding]::Unicode.GetBytes($coreCompileCmd)
$coreCompileB64 = [Convert]::ToBase64String($coreCompileBytes)
$coreCompileProc = Start-Process powershell -ArgumentList "-NoProfile", "-EncodedCommand", $coreCompileB64 -WindowStyle Hidden -Wait -PassThru
if ($coreCompileProc.ExitCode -ne 0) {
  Write-Error "  核心系统编译失败 (ExitCode: $($coreCompileProc.ExitCode))，请检查 Maven 输出"
  if (Test-Path "$CORE_PROJECT_PATH\compile.log") {
    Write-Host "  ----- core compile.log 尾部 -----" -ForegroundColor Yellow
    Get-Content "$CORE_PROJECT_PATH\compile.log" -Tail 50 | ForEach-Object { Write-Host "  $_" }
  }
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
Assert-NoEcjErrorClasses -ProjectPath $CORE_PROJECT_PATH -Label "Core"

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
Add-Content -Path $coreBat -Value "java $coreJavaArgs -jar `"target\$CORE_JAR`" --server.port=$CORE_PORT >> `"$CORE_PROJECT_PATH\core.log`" 2>&1"
$coreProc = Start-Process $coreBat -WindowStyle Hidden -PassThru
if (Wait-Url "http://127.0.0.1:$CORE_PORT/actuator/health" -MaxTries 60 -SleepSec 3) {
  Write-Host "  核心系统启动成功, 端口: $CORE_PORT (OTel Agent 已注入)" -ForegroundColor Green
} else {
  Write-Warning "  核心系统健康检查超时, 请检查控制台日志"
  if (Test-Path "$CORE_PROJECT_PATH\core.log") {
    Write-Host "  ----- core.log 尾部 -----" -ForegroundColor Yellow
    Get-Content "$CORE_PROJECT_PATH\core.log" -Tail 50 | ForEach-Object { Write-Host "  $_" }
  }
  Stop-AllAndExit
}
Set-Location $ROOT

# ======================================
# 步骤5：前端 React
# ======================================

Write-Host "`n=== 5/6 启动前端 ($FRONTEND_PORT) ===" -ForegroundColor Green

$feStopped = Stop-PortOccupier -Port $FRONTEND_PORT
if (-not $feStopped) {
  Write-Warning "  端口 $FRONTEND_PORT 仍被占用，无法释放"
  Stop-AllAndExit
}
Set-Location $OBS_FRONTEND_PATH
if (Test-Path (Join-Path $OBS_FRONTEND_PATH "node_modules")) {
  $feLog = Join-Path $OBS_FRONTEND_PATH "frontend.log"
  $feProc = Start-Process cmd -ArgumentList "/c","npm run dev > `"$feLog`" 2>&1" -WindowStyle Hidden -PassThru
  if (Wait-Url "http://127.0.0.1:$FRONTEND_PORT/" -MaxTries 30 -SleepSec 2) {
    Write-Host "  前端启动成功, 端口: $FRONTEND_PORT" -ForegroundColor Green
  } else {
    Write-Warning "  前端启动超时"
    Stop-AllAndExit
  }
} else {
  Write-Warning "  node_modules 缺失, 请执行: cd '$OBS_FRONTEND_PATH'; npm install"
  Stop-AllAndExit
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
Write-Host "  运行日志 (排障用):" -ForegroundColor Cyan
Write-Host "    Redis    : $ROOT\redis.log" -ForegroundColor DarkGray
Write-Host "    Backend  : $OBS_BACKEND_PATH\backend.log" -ForegroundColor DarkGray
Write-Host "    Collector: $COLLECTOR_PATH\collector.log" -ForegroundColor DarkGray
Write-Host "    Core     : $CORE_PROJECT_PATH\core.log" -ForegroundColor DarkGray
Write-Host "    Frontend : $OBS_FRONTEND_PATH\frontend.log" -ForegroundColor DarkGray
Write-Host "`n启动器任务完成，正在退出。所有 5 个服务已在后台脱离启动器独立运行。" -ForegroundColor Green
Write-Host "  关闭本窗口、终止启动器进程或按 Ctrl+C 都不会停止它们。" -ForegroundColor DarkGray
Write-Host "  如需停止全部服务，请另开终端运行: scripts/stop-all.ps1" -ForegroundColor Cyan
} finally {
  # 关键修复：启动器退出（含 Ctrl+C / 被外部 kill）不再杀服务。
  # 服务均以 Start-Process -WindowStyle Hidden 启动，独立于启动器进程树。
  if ($global:CtrlC) {
    Write-Host "[启动器] 已退出；后台服务（Redis/Collector/Backend/Core/Frontend）不受影响，停止请运行 scripts/stop-all.ps1。" -ForegroundColor Cyan
  }
}
