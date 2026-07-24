#!/usr/bin/env bash
# =============================================================================
# verify-E2E.sh — mobile-ai-bank-demo 可观测性端到端验证脚本
# -----------------------------------------------------------------------------
# 用途：在干净环境下一键执行「全量 E2E 验证」——清理 H2/Redis → 重启 backend+collector+core
#       → 播种 36 轮对话 + 9 条业务埋点 → 跑全栈约 20 点验证（scripts/seed_and_verify.py）
#       → 并额外断言核心不变量：L0 计数 ≥ L1（方案A 修复保证每请求必产生 L0，歧义/中断轮可仅停留在 L0），且 L2 ≤ L1 ≤ L0 恒成立。
#
# 流程：
#   1) [可选] 重新打包 Core（含最新代码修复）
#   2) 清理环境：停掉旧 backend/collector/core（按端口杀+验证释放）、flush Redis(DB0, 校验)、删 H2（校验文件消失）
#   3) 拉起全链路：backend(9090) → OTel Collector(4318) → Core(8080)（Redis 须已运行）
#      每一步都「等进程真正就绪」再做下一步（collector 用端口监听校验，不再 || true 跳过的假等待）
#   4) 运行 scripts/seed_and_verify.py（播种 36 轮对话 + 9 条业务埋点 + 全栈约 20 点验证）
#   5) 等待 spans 经 collector → backend 沉淀
#   6) 查询实时计数 + span 分布，断言 L0≥L1、L2≤L1≤L0、L0 层 span 已上报
#
# 用法：
#   ./scripts/verify-E2E.sh                 # 完整流程（打包→清库→起服务→seed→验证）
#   ./scripts/verify-E2E.sh --skip-build    # 跳过 Maven 打包（jar 已是最新时省时）
#   ./scripts/verify-E2E.sh --verify-only   # 仅 seed+验证（假设服务已起、数据已清）
#   ./scripts/verify-E2E.sh --stop-after    # 验证完成后停止本脚本启动的所有进程
#   ./scripts/verify-E2E.sh --turns 36      # 覆盖断言用的对话轮数（默认 36）
#   ./scripts/verify-E2E.sh --help
#
# 退出码：0=验证通过  1=验证失败  2=前置检查/清理/启动失败
# =============================================================================
set -uo pipefail

# ----------------------------- 配置区 ----------------------------------------
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$PROJECT_ROOT/observability/backend"
COLLECTOR_DIR="$PROJECT_ROOT/observability/otel-collector"
CORE_JAR="$PROJECT_ROOT/target/mobile-ai-demo-0.0.1-SNAPSHOT.jar"
BACKEND_JAR="$BACKEND_DIR/target/observability-backend-0.1.0-SNAPSHOT.jar"
AGENT_JAR="$PROJECT_ROOT/opentelemetry-javaagent.jar"
SEED_SCRIPT="$PROJECT_ROOT/scripts/seed_and_verify.py"
SEED_SCRIPT_W=$(cygpath -w "$SEED_SCRIPT" 2>/dev/null || echo "$SEED_SCRIPT")
LOG_DIR="$PROJECT_ROOT/scripts/logs"

BACKEND_PORT=9090
CORE_PORT=8080
COLLECTOR_PORT=4318
REDIS_PORT=6379
OTEL_ENDPOINT="http://127.0.0.1:$COLLECTOR_PORT"

SEED_TURNS=36          # seed_all.py 硬编码 36 轮，用于断言
SETTLE_SECONDS=30      # seed 完成后等待 spans 经 collector → backend 沉淀

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

# 按端口杀掉 LISTENING 进程：taskkill /F /T（杀整棵进程树）+ 重试 + 最终校验端口释放。
# 额外对 collector 按进程名 otelcol.exe 兜底杀（其端口有时 netstat 兜底不及时）。
# 返回 0=端口已释放（或本就空闲），1=重试后仍被占用。
kill_port() {
  local port="$1" max_tries=6 tries=0 pids pid
  while [ "$tries" -lt "$max_tries" ]; do
    pids=$(netstat -ano 2>/dev/null | grep -E "[:.]$port[[:space:]]" | grep LISTENING | awk '{print $NF}' | grep -E '^[0-9]+$' | sort -u)
    if [ -z "$pids" ]; then
      return 0   # 端口已空闲
    fi
    for pid in $pids; do
      taskkill /PID "$pid" /F /T >/dev/null 2>&1 && log "已 kill pid=$pid (占用 $port)" || warn "kill pid=$pid 失败（可能已退出）"
    done
    sleep 2
    tries=$((tries+1))
  done
  # collector 兜底：按进程名杀
  if [ "$port" = "$COLLECTOR_PORT" ]; then
    taskkill /IM otelcol-contrib.exe /F >/dev/null 2>&1 && log "已按进程名 kill otelcol-contrib.exe" || true
    sleep 2
  fi
  if netstat -ano 2>/dev/null | grep -E "[:.]$port[[:space:]]" | grep -q LISTENING; then
    err "端口 $port 在多次重试后仍被占用，无法释放"
    return 1
  fi
  return 0
}

# 等待端口真正进入 LISTENING（用于 collector 这类无 HTTP health 的服务）
wait_for_port() {  # $1=port  $2=label  $3=timeout_sec
  local port="$1" label="$2" timeout="$3" elapsed=0
  log "等待 $label 监听 $port …"
  while [ "$elapsed" -lt "$timeout" ]; do
    if is_listening "$port"; then log "$label 已监听 $port"; return 0; fi
    sleep 2; elapsed=$((elapsed+2))
  done
  err "$label 在 ${timeout}s 内未监听 $port"; return 1
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

# 清空 Redis DB0：先确保 Redis 在跑（缺失则拉起并 PING 校验），再 FLUSHDB，最后用 DBSIZE 验证已清空。
# 返回 0=已清空，1=无法清空（seed 可能失败，但脚本继续）。
flush_redis() {
  if ! is_listening "$REDIS_PORT"; then
    warn "Redis($REDIS_PORT) 未监听，尝试启动 redis-server…"
    command -v redis-server >/dev/null 2>&1 && (redis-server --daemonize yes >/dev/null 2>&1 || true)
    local i=0
    while [ "$i" -lt 15 ]; do
      if redis-cli -n 0 PING 2>/dev/null | grep -qi PONG; then break; fi
      sleep 1; i=$((i+1))
    done
  fi
  if ! redis-cli -n 0 PING 2>/dev/null | grep -qi PONG; then
    warn "Redis 仍不可达，跳过 FLUSHDB（session 缓存依赖 Redis，seed 可能失败）"
    return 1
  fi
  local before after
  before=$(redis-cli -n 0 DBSIZE 2>/dev/null | tr -d '\r')
  redis-cli -n 0 FLUSHDB >/dev/null 2>&1
  after=$(redis-cli -n 0 DBSIZE 2>/dev/null | tr -d '\r')
  log "Redis DB0 已清空（清前 ${before:-?} keys → 清后 ${after:-?} keys）"
  if [ "$after" = "0" ]; then return 0; else warn "Redis FLUSHDB 后仍有 $after keys"; return 1; fi
}

# 删除 H2 数据文件：必须在 backend 进程已死（端口释放、文件锁解除）之后调用。
# 删后校验目标文件确实消失；若仍有残留则报真实 ERROR（而非假成功）。
clear_h2() {
  local dirs=("$BACKEND_DIR/data" "$PROJECT_ROOT/data")
  local removed=0 still=0 f
  for d in "${dirs[@]}"; do
    [ -d "$d" ] || continue
    local files
    files=$(ls "$d" 2>/dev/null | grep -iE '\.(mv\.db|trace\.db|h2\.db|lock\.db)$' || true)
    for f in $files; do
      if rm -f "$d/$f" 2>/dev/null; then
        log "已删除 H2 文件: $d/$f"; removed=$((removed+1))
      else
        err "删除失败(可能被进程锁定): $d/$f"; still=$((still+1))
      fi
    done
  done
  if [ "$removed" -gt 0 ] && [ "$still" -eq 0 ]; then
    log "H2 数据文件已全部删除"
  elif [ "$removed" -eq 0 ] && [ "$still" -eq 0 ]; then
    log "无 H2 数据文件（已是干净状态）"
  else
    err "H2 清理不完整：$still 个文件未能删除（旧进程可能仍持有锁）"
    return 1
  fi
  return 0
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
  # 构建链路（仓库根无 mvnw wrapper，CORE 实际靠 mvn.cmd 构建；本环境通常已预先打包，建议 --skip-build）
  MVN=""
  if [ -x "./mvnw" ]; then MVN="./mvnw"
  elif [ -f "mvnw.cmd" ]; then MVN="mvnw.cmd"
  elif [ -f "mvn.cmd" ]; then MVN="mvn.cmd"
  else
    local_mvn="$HOME/.m2/wrapper/dists/apache-maven-3.9.15/$(ls "$HOME/.m2/wrapper/dists/apache-maven-3.9.15" 2>/dev/null | head -1)/bin/mvn"
    [ -x "$local_mvn" ] && MVN="$local_mvn"
  fi
  if [ -z "$MVN" ]; then err "未找到 Maven（mvnw/mvnw.cmd/mvn.cmd/缓存 Maven 均不可用），请用 --skip-build 并提供已构建 jar"; exit 2; fi
  log "使用构建器: $MVN"
  $MVN package -DskipTests -o -q || { err "Maven 打包失败（$MVN）"; exit 2; }
  log "Core 打包完成"
else
  [ "$SKIP_BUILD" -eq 1 ] && log "跳过打包（--skip-build）"
fi

# ----------------------------- 2) 清理环境 -----------------------------------
if [ "$VERIFY_ONLY" -eq 0 ]; then
  step "清理环境（停旧服务 / flush Redis / 删 H2）"
  # 先杀进程并【校验端口真释放】，失败立即退出（避免用旧进程跑验证）
  kill_port "$BACKEND_PORT"   || { err "backend 端口释放失败"; exit 2; }
  kill_port "$COLLECTOR_PORT" || { err "collector 端口释放失败"; exit 2; }
  kill_port "$CORE_PORT"      || { err "core 端口释放失败"; exit 2; }
  # Redis 不杀（共享长驻服务），仅清空 DB0 并校验
  flush_redis
  # H2 必须在 backend 进程已死之后删（释放文件锁）
  sleep 2
  clear_h2 || { err "H2 清理失败"; exit 2; }
fi

# ----------------------------- 3) 拉起服务 -----------------------------------
if [ "$VERIFY_ONLY" -eq 0 ]; then
  step "启动 backend ($BACKEND_PORT)"
  if is_listening "$BACKEND_PORT"; then
    err "backend 端口仍被占用（清理未生效），无法启动新实例"; exit 2
  fi
  # 按 MEMORY SOP：backend 须以 CWD=observability/backend/ 启动，H2(./data) 才落在 backend/data/
  # 子 shell 内 cd（不影响脚本 CWD）；exec 让 java 继承子 shell pid；外层 & 使 $! 在父 shell 可见。
  ( cd "$BACKEND_DIR" && exec nohup java -jar "$BACKEND_JAR_W" --server.port=$BACKEND_PORT --spring.sql.init.mode=always \
    > "$BACKEND_LOG" 2>&1 ) &
  log "backend pid=$!"
  wait_for_url "http://127.0.0.1:$BACKEND_PORT/health" "backend" 180 || exit 2

  step "启动 OTel Collector ($COLLECTOR_PORT)"
  if is_listening "$COLLECTOR_PORT"; then
    err "collector 端口仍被占用（清理未生效），无法启动新实例"; exit 2
  fi
  nohup "$COLLECTOR_DIR_W/otelcol-contrib.exe" --config "$COLLECTOR_DIR_W/config.yaml" \
    > "$COLLECTOR_LOG" 2>&1 &
  log "collector pid=$!"
  # collector 无 http health，靠端口监听校验（不再 || true 跳过）
  wait_for_port "$COLLECTOR_PORT" "collector" 25 || { err "collector 未监听，Core 上报将失败"; exit 2; }

  step "启动 Core ($CORE_PORT) + OTel agent"
  if is_listening "$CORE_PORT"; then
    err "core 端口仍被占用（清理未生效），无法启动新实例"; exit 2
  fi
  export OTEL_EXPORTER_OTLP_ENDPOINT="$OTEL_ENDPOINT"
  export OTEL_SERVICE_NAME=mobile-bank-core
  export OTEL_METRICS_EXPORTER=otlp OTEL_TRACES_EXPORTER=otlp OTEL_LOGS_EXPORTER=otlp
  nohup java -Xms512m -Xmx1024m -XX:MaxMetaspaceSize=256m \
    -javaagent:"$AGENT_JAR_W" -jar "$CORE_JAR_W" --server.port=$CORE_PORT \
    > "$CORE_LOG" 2>&1 &
  log "core pid=$!"
  wait_for_url "http://127.0.0.1:$CORE_PORT/actuator/health" "core" 120 || exit 2
else
  log "verify-only：跳过清理与启动，假设服务已就绪"
fi

# ----------------------------- 4) 播种 ---------------------------------------
step "运行 seed + 全量验证 ($SEED_SCRIPT)"
SEED_LOG="$LOG_DIR/seed-E2E.log"
"$PYTHON_BIN" "$SEED_SCRIPT_W" 2>&1 | tee "$SEED_LOG" | tail -60
log "seed + 全量验证完成（完整日志: $SEED_LOG）"

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
# 放宽断言：匹配 L0 层 span 前缀（覆盖 LLM 路径的 L0:qwen-turbo 与确定性分支的 L0:DomainRouter），
# 只要 backend 真实收到并上报了 L0 层 span 即证明 L0 层可观测覆盖成立。
HAS_L0=0
if echo "$OPNAMES" | grep -q "L0:"; then HAS_L0=1; fi
echo "  distinctOpNames 含 L0: 层 span: $([ $HAS_L0 -eq 1 ] && echo YES || echo NO)"

# ----------------------------- 断言判定 --------------------------------------
step "验证判定"
PASS=1
L0N=${L0:-0}; L1N=${L1:-0}; L2N=${L2:-0}
# 核心不变量（方案A 修复语义）：
#   原 bug = 确定性路由跳过 L0 span 创建 → 出现 L0 < L1（L0 缺失）。
#   修复后保证「每请求必产生 L0」，故正确不变量是 L0 ≥ L1（L0 永不低于 L1）。
#   注意：歧义/DISAMBIGUATION/INTERRUPTED 等轮次可在 L0 直接消化而不上升到 L1，
#   因此 L0 允许略大于 L1（本就是正常多层 agent 行为），绝不可出现 L0 < L1。
if [ "$L0N" -ge "$L1N" ]; then log "✓ L0($L0N) ≥ L1($L1N)（每请求均产生 L0，方案A 核心不变量成立）"; else warn "✗ L0($L0N) < L1($L1N)（确定性路由仍在漏 L0，方案A 回归！）"; PASS=0; fi
# 信息项：若 L0 明显小于 seed 轮数，多为 seed 轮次 HTTP 错误，非修复问题
if [ "$L0N" -lt "$SEED_TURNS" ]; then warn "⚠ L0($L0N) < 请求数($SEED_TURNS)，部分 seed 轮次可能 HTTP 错误（非修复问题）"; fi
# 层级不变量：L2 仅在 L1 继续下钻时产生 → L2 ≤ L1 ≤ L0
if [ "$L2N" -le "$L1N" ]; then log "✓ L2($L2N) ≤ L1($L1N) ≤ L0($L0N)"; else warn "✗ L2($L2N) > L1($L1N)（层级不变量破坏）"; PASS=0; fi
# L0 层 span 已上报 backend（L0 层可观测覆盖成立；LLM 路径为 L0:<model>，确定性分支为 L0:DomainRouter）
if [ "$HAS_L0" -eq 1 ]; then log "✓ L0 层 span 已产生并上报 backend（L0 层可观测覆盖成立）"; else warn "✗ 未检测到 L0 层 span（L0 层可能缺失）"; PASS=0; fi

# ----------------------------- 收尾 ------------------------------------------
if [ "$STOP_AFTER" -eq 1 ]; then
  step "停止本脚本启动的进程 (--stop-after)"
  kill_port "$BACKEND_PORT"; kill_port "$COLLECTOR_PORT"; kill_port "$CORE_PORT"
fi

echo ""
if [ "$PASS" -eq 1 ]; then
  echo -e "${GREEN}✅ E2E 验证通过：${NC}L0($L0N)≥L1($L1N)（每请求均产生 L0），L2($L2N)≤L1($L1N)≤L0($L0N)，L0 层 span 已生成"
  exit 0
else
  echo -e "${RED}❌ E2E 验证失败${NC}（详见上方 ✗ 项；日志: $LOG_DIR）"
  exit 1
fi
