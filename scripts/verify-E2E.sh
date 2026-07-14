#!/usr/bin/env bash
# =============================================================================
# verify-E2E.sh — mobile-ai-bank-demo 可观测性端到端验证脚本
# -----------------------------------------------------------------------------
# 用途：在干净环境下一键验证「方案A — DomainRouter 确定性路由补全 L0 span」等
#       可观测性修复，核心断言：L0 计数 = 请求数(=L1)，且 L2 ≤ L0 恒成立。
#
# 流程：
#   1) [可选] 重新打包 Core（含最新代码修复）
#   2) 清理环境：停掉旧 backend/collector/core、flush Redis(DB0)、删 H2 文件
#   3) 拉起全链路：backend(9090) → OTel Collector(4318) → Core(8080)（Redis 须已运行）
#   4) 运行 test/seed_all.py 播种 36 轮对话
#   5) 等待 spans 经 collector → backend 沉淀
#   6) 查询实时计数 + span 分布，断言 L0=L1=请求数、L2≤L0、L0:DomainRouter 存在
#
# 用法：
#   ./scripts/verify-E2E.sh                 # 完整流程（打包→清库→起服务→seed→验证）
#   ./scripts/verify-E2E.sh --skip-build    # 跳过 Maven 打包（jar 已是最新时省时）
#   ./scripts/verify-E2E.sh --verify-only   # 仅 seed+验证（假设服务已起、数据已清）
#   ./scripts/verify-E2E.sh --stop-after    # 验证完成后停止本脚本启动的所有进程
#   ./scripts/verify-E2E.sh --turns 36      # 覆盖断言用的对话轮数（默认 36）
#   ./scripts/verify-E2E.sh --help
#
# 退出码：0=验证通过  1=验证失败  2=前置检查失败
# =============================================================================
set -uo pipefail

# ----------------------------- 配置区 ----------------------------------------
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$PROJECT_ROOT/observability/backend"
COLLECTOR_DIR="$PROJECT_ROOT/observability/otel-collector"
CORE_JAR="$PROJECT_ROOT/target/mobile-ai-demo-0.0.1-SNAPSHOT.jar"
BACKEND_JAR="$BACKEND_DIR/target/observability-backend-0.1.0-SNAPSHOT.jar"
AGENT_JAR="$PROJECT_ROOT/opentelemetry-javaagent.jar"
SEED_SCRIPT="$PROJECT_ROOT/test/seed_all.py"
SEED_SCRIPT_W=$(cygpath -w "$SEED_SCRIPT" 2>/dev/null || echo "$SEED_SCRIPT")
LOG_DIR="$PROJECT_ROOT/scripts/logs"

BACKEND_PORT=9090
CORE_PORT=8080
COLLECTOR_PORT=4318
REDIS_PORT=6379
OTEL_ENDPOINT="http://127.0.0.1:$COLLECTOR_PORT"

SEED_TURNS=36          # seed_all.py 硬编码 36 轮，用于断言
SETTLE_SECONDS=20      # seed 完成后等待 spans 沉淀

# 原生 Windows 程序（java / otelcol.exe）所需的 Windows 风格路径（cygpath -w）。
# Git Bash 会把 $PROJECT_ROOT 解析成 /d/... 的 MSYS 路径，直接传给 java -jar 会
# 因无法识别且项目路径含空格而报 "Unable to access jarfile"。必须转成 D:\... 形式。
if command -v cygpath >/dev/null 2>&1; then
  CORE_JAR_W=$(cygpath -w "$CORE_JAR")
  BACKEND_JAR_W=$(cygpath -w "$BACKEND_JAR")
  AGENT_JAR_W=$(cygpath -w "$AGENT_JAR")
  COLLECTOR_DIR_W=$(cygpath -w "$COLLECTOR_DIR")
else
  CORE_JAR_W="$CORE_JAR"; BACKEND_JAR_W="$BACKEND_JAR"
  AGENT_JAR_W="$AGENT_JAR"; COLLECTOR_DIR_W="$COLLECTOR_DIR"
fi

# 选 Python：优先 $PYTHON 环境变量，其次 WorkBuddy 托管解释器，再次 PATH
PYTHON_BIN="${PYTHON:-}"
if [ -z "$PYTHON_BIN" ]; then
  WB_PY="$HOME/.workbuddy/binaries/python/versions/3.13.12/python.exe"
  if [ -x "$WB_PY" ]; then PYTHON_BIN="$WB_PY"
  elif command -v python3 >/dev/null 2>&1; then PYTHON_BIN=python3
  elif command -v python  >/dev/null 2>&1; then PYTHON_BIN=python
  fi
fi

# ----------------------------- 命令行参数 ------------------------------------
SKIP_BUILD=0
VERIFY_ONLY=0
STOP_AFTER=0
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1 ;;
    --verify-only) VERIFY_ONLY=1 ;;
    --stop-after) STOP_AFTER=1 ;;
    --turns) SEED_TURNS="$2"; shift ;;
    --help|-h)
      grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数: $1 (--help 查看用法)"; exit 2 ;;
  esac
  shift
done

# ----------------------------- 日志/颜色 -------------------------------------
GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
log()  { echo -e "${GREEN}[verify-E2E]${NC} $*"; }
warn() { echo -e "${YELLOW}[verify-E2E][WARN]${NC} $*"; }
err()  { echo -e "${RED}[verify-E2E][ERROR]${NC} $*"; }
step() { echo -e "${CYAN}== $* ==${NC}"; }

mkdir -p "$LOG_DIR"
BACKEND_LOG="$LOG_DIR/backend-E2E.log"
COLLECTOR_LOG="$LOG_DIR/collector-E2E.log"
CORE_LOG="$LOG_DIR/core-E2E.log"

# ----------------------------- 工具函数 --------------------------------------
is_listening() {  # $1=port  — 仅认 LISTENING 状态，排除 TIME_WAIT 误判
  netstat -ano 2>/dev/null | grep -E "[:.]$1[[:space:]]" | grep -q LISTENING
}
stop_port() {  # $1=port  — taskkill 占用该端口的 LISTENING 进程（释放 jar/H2 锁）
  local port="$1"
  local pids
  pids=$(netstat -ano 2>/dev/null | grep -E "[:.]$port[[:space:]]" | grep LISTENING | awk '{print $NF}' | grep -E '^[0-9]+$' | sort -u)
  for pid in $pids; do
    taskkill /PID "$pid" /F >/dev/null 2>&1 && log "已停止占用 $port 的进程 pid=$pid" || warn "停止 pid=$pid 失败（可能已退出）"
  done
}
wait_for_url() {  # $1=url  $2=label  $3=timeout_sec
  local url="$1" label="$2" timeout="$3" elapsed=0
  log "等待 $label 就绪 ($url)…"
  while [ "$elapsed" -lt "$timeout" ]; do
    if curl -s -m 4 "$url" >/dev/null 2>&1; then log "$label 已就绪"; return 0; fi
    sleep 3; elapsed=$((elapsed+3))
  done
  err "$label 在 ${timeout}s 内未就绪"; return 1
}
json_get() {  # $1=json  $2=dotted key (data.l0Calls)  — 用 python 解析
  echo "$1" | "$PYTHON_BIN" -c "import sys,json
try:
    d=json.load(sys.stdin)
    for k in '$2'.split('.'): d=d.get(k) if isinstance(d,dict) else None
    print(d if d is not None else '')
except Exception: print('')" 2>/dev/null
}

# ----------------------------- 前置检查 --------------------------------------
step "前置检查"
if ! command -v java >/dev/null 2>&1; then err "未找到 java，请先配置 JAVA_HOME"; exit 2; fi
if [ -z "$PYTHON_BIN" ]; then err "未找到 python，请设置 PYTHON 环境变量或安装 python3"; exit 2; fi
if ! command -v redis-cli >/dev/null 2>&1; then err "未找到 redis-cli"; exit 2; fi
if ! command -v netstat >/dev/null 2>&1; then err "未找到 netstat"; exit 2; fi
if ! command -v curl >/dev/null 2>&1; then err "未找到 curl"; exit 2; fi
if [ ! -f "$SEED_SCRIPT" ]; then err "缺失 seed 脚本: $SEED_SCRIPT"; exit 2; fi
if [ "$VERIFY_ONLY" -eq 0 ]; then
  if [ ! -f "$AGENT_JAR" ]; then err "缺失 javaagent: $AGENT_JAR"; exit 2; fi
  if [ ! -f "$BACKEND_JAR" ]; then err "缺失 backend jar: $BACKEND_JAR（请先构建）"; exit 2; fi
  if [ "$SKIP_BUILD" -eq 0 ] && [ ! -f "$CORE_JAR" ]; then err "缺失 core jar: $CORE_JAR（请先构建或不加 --skip-build）"; exit 2; fi
fi
log "Python: $PYTHON_BIN"

# ----------------------------- 1) 打包 Core ---------------------------------
if [ "$VERIFY_ONLY" -eq 0 ] && [ "$SKIP_BUILD" -eq 0 ]; then
  step "重新打包 Core（离线 Maven，含最新修复）"
  cd "$PROJECT_ROOT"
  if [ -x "./mvnw" ] && [ -f "mvnw.jar" ]; then
    ./mvnw package -DskipTests -o -q || { err "mvnw 打包失败"; exit 2; }
  else
    # 回退到缓存 Maven（见项目 MEMORY.md Maven SOP）
    local_mvn="$HOME/.m2/wrapper/dists/apache-maven-3.9.15/$(ls "$HOME/.m2/wrapper/dists/apache-maven-3.9.15" 2>/dev/null | head -1)/bin/mvn"
    if [ -x "$local_mvn" ]; then "$local_mvn" package -DskipTests -o -q || { err "缓存 Maven 打包失败"; exit 2; }
    else err "未找到 Maven（mvnw 与缓存 Maven 均不可用）"; exit 2; fi
  fi
  log "Core 打包完成"
else
  [ "$SKIP_BUILD" -eq 1 ] && log "跳过打包（--skip-build）"
fi

# ----------------------------- 2) 清理环境 -----------------------------------
if [ "$VERIFY_ONLY" -eq 0 ]; then
  step "清理环境（停旧服务 / flush Redis / 删 H2）"
  stop_port "$BACKEND_PORT"
  stop_port "$COLLECTOR_PORT"
  stop_port "$CORE_PORT"
  sleep 2
  # Redis：检查是否在跑，缺失则尝试拉起
  if ! is_listening "$REDIS_PORT"; then
    warn "Redis($REDIS_PORT) 未监听，尝试启动 redis-server…"
    command -v redis-server >/dev/null 2>&1 && (redis-server --daemonize yes >/dev/null 2>&1 || true)
    sleep 2
  fi
  if is_listening "$REDIS_PORT"; then
    redis-cli -n 0 FLUSHDB >/dev/null 2>&1 && log "Redis DB0 已清空" || warn "Redis FLUSHDB 失败"
  else
    warn "Redis 仍未运行，seed 可能失败（session 缓存依赖 Redis）"
  fi
  # 删 H2：backend 的 H2 路径是相对的 ./data/observability，落点取决于其 CWD。
  # 脚本按 MEMORY SOP 以 CWD=backend 目录启动 → 落在 backend/data/；但历史残留可能在
  # 项目根 data/（早期未 cd 启动时写入）。两处都清理，避免数据累积导致计数失真。
  sleep 3   # 等被 kill 的进程释放文件锁，避免 Windows 下删除被延迟
  rm -f "$BACKEND_DIR"/data/*.mv.db "$BACKEND_DIR"/data/*.trace.db 2>/dev/null
  rm -f "$PROJECT_ROOT"/data/*.mv.db "$PROJECT_ROOT"/data/*.trace.db 2>/dev/null
  log "H2 数据文件已删除"
fi

# ----------------------------- 3) 拉起服务 -----------------------------------
if [ "$VERIFY_ONLY" -eq 0 ]; then
  step "启动 backend ($BACKEND_PORT)"
  stop_port "$BACKEND_PORT"; sleep 1   # 兜底：确保端口空闲（应对 PID 漂变/残留）
  if is_listening "$BACKEND_PORT"; then warn "backend 端口已占用，跳过启动"; else
    # 按 MEMORY SOP：backend 须以 CWD=observability/backend/ 启动，H2(./data) 才落在 backend/data/
    # 子 shell 内 cd（不影响脚本 CWD）；exec 让 java 继承子 shell pid；外层 & 使 $! 在父 shell 可见。
    ( cd "$BACKEND_DIR" && exec nohup java -jar "$BACKEND_JAR_W" --server.port=$BACKEND_PORT --spring.sql.init.mode=always \
      > "$BACKEND_LOG" 2>&1 ) &
    log "backend pid=$!"
  fi
  wait_for_url "http://127.0.0.1:$BACKEND_PORT/health" "backend" 90 || exit 2

  step "启动 OTel Collector ($COLLECTOR_PORT)"
  stop_port "$COLLECTOR_PORT"; sleep 1
  if is_listening "$COLLECTOR_PORT"; then warn "collector 端口已占用，跳过启动"; else
    nohup "$COLLECTOR_DIR_W/otelcol.exe" --config "$COLLECTOR_DIR_W/config.yaml" \
      > "$COLLECTOR_LOG" 2>&1 &
    log "collector pid=$!"
    wait_for_url "http://127.0.0.1:$COLLECTOR_PORT/" "collector" 20 || true  # collector 无 http health，靠端口
    is_listening "$COLLECTOR_PORT" && log "collector 已监听 $COLLECTOR_PORT" || { err "collector 未监听"; exit 2; }
  fi

  step "启动 Core ($CORE_PORT) + OTel agent"
  stop_port "$CORE_PORT"; sleep 1
  if is_listening "$CORE_PORT"; then warn "core 端口已占用，跳过启动"; else
    export OTEL_EXPORTER_OTLP_ENDPOINT="$OTEL_ENDPOINT"
    export OTEL_SERVICE_NAME=mobile-bank-core
    export OTEL_METRICS_EXPORTER=otlp OTEL_TRACES_EXPORTER=otlp OTEL_LOGS_EXPORTER=otlp
    nohup java -Xms512m -Xmx1024m -XX:MaxMetaspaceSize=256m \
      -javaagent:"$AGENT_JAR_W" -jar "$CORE_JAR_W" --server.port=$CORE_PORT \
      > "$CORE_LOG" 2>&1 &
    log "core pid=$!"
  fi
  wait_for_url "http://127.0.0.1:$CORE_PORT/actuator/health" "core" 90 || exit 2
else
  log "verify-only：跳过清理与启动，假设服务已就绪"
fi

# ----------------------------- 4) 播种 ---------------------------------------
step "运行 seed ($SEED_SCRIPT)"
"$PYTHON_BIN" "$SEED_SCRIPT_W" 2>&1 | tail -5
log "seed 完成"

# ----------------------------- 5) 沉淀 + 6) 验证 -----------------------------
step "等待 spans 沉淀 (${SETTLE_SECONDS}s)"
sleep "$SETTLE_SECONDS"

step "查询实时计数"
RT=$(curl -s -m 10 "http://127.0.0.1:$BACKEND_PORT/api/v1/metrics/realtime")
L0=$(json_get "$RT" "data.l0Calls"); L1=$(json_get "$RT" "data.l1Calls"); L2=$(json_get "$RT" "data.l2Calls")
echo "  l0Calls = ${L0:-?}"
echo "  l1Calls = ${L1:-?}"
echo "  l2Calls = ${L2:-?}"

step "查询 span 分布"
STATS=$(curl -s -m 10 "http://127.0.0.1:$BACKEND_PORT/api/v1/traces/debug/span-stats")
OPNAMES=$(json_get "$STATS" "data.distinctOpNames")
HAS_DOMAIN_ROUTER=0
if echo "$OPNAMES" | grep -q "L0:DomainRouter"; then HAS_DOMAIN_ROUTER=1; fi
echo "  distinctOpNames 含 L0:DomainRouter: $([ $HAS_DOMAIN_ROUTER -eq 1 ] && echo YES || echo NO)"

# ----------------------------- 断言判定 --------------------------------------
step "验证判定"
PASS=1
L0N=${L0:-0}; L1N=${L1:-0}; L2N=${L2:-0}
# 核心不变量：每请求恰好一个 L0，故 L0 必须等于 L1（原 bug 正是 L0<L1，
# 因确定性路由跳过 L0）。此判据对 seed 偶发 HTTP 错误免疫（错误同时影响 L0/L1）。
if [ "$L0N" -eq "$L1N" ]; then log "✓ L0($L0N) = L1($L1N)（每请求一个 L0，方案A 核心不变量成立）"; else warn "✗ L0($L0N) ≠ L1($L1N)（确定性路由可能仍在漏 L0）"; PASS=0; fi
# 信息项：若 L0 略小于 seed 轮数，多为 seed 轮次 HTTP 错误，非修复问题
if [ "$L0N" -lt "$SEED_TURNS" ]; then warn "⚠ L0($L0N) < 请求数($SEED_TURNS)，部分 seed 轮次可能 HTTP 错误（非修复问题）"; fi
# L2 ≤ L0
if [ "$L2N" -le "$L0N" ]; then log "✓ L2($L2N) ≤ L0($L0N)"; else warn "✗ L2($L2N) > L0($L0N)"; PASS=0; fi
# 确定性路由 L0 span 存在（方案A 生效铁证）
if [ "$HAS_DOMAIN_ROUTER" -eq 1 ]; then log "✓ 确定性路由 L0:DomainRouter span 已产生（方案A 生效）"; else warn "✗ 未检测到 L0:DomainRouter（方案A 可能未生效）"; PASS=0; fi

# ----------------------------- 收尾 ------------------------------------------
if [ "$STOP_AFTER" -eq 1 ]; then
  step "停止本脚本启动的进程 (--stop-after)"
  stop_port "$BACKEND_PORT"; stop_port "$COLLECTOR_PORT"; stop_port "$CORE_PORT"
fi

echo ""
if [ "$PASS" -eq 1 ]; then
  echo -e "${GREEN}✅ E2E 验证通过：${NC}L0($L0N)=L1($L1N)（=请求数），L2($L2N)≤L0($L0N)，L0:DomainRouter 已生成"
  exit 0
else
  echo -e "${RED}❌ E2E 验证失败${NC}（详见上方 ✗ 项；日志: $LOG_DIR）"
  exit 1
fi
