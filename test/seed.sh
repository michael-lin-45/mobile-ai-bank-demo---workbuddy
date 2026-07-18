#!/bin/bash
# Seed script v2 — correct field name "message"
# session_id 随机化: 每次运行生成独立 RUN 前缀, 避免重播叠加旧会话 (fix #3 污染)
BASE="http://127.0.0.1:8080/api/bank/chat"

# 每轮播种使用随机 run 前缀（epoch 秒 + $RANDOM），保证重播不会追加到旧会话
RUN_ID="s$(date +%s)${RANDOM}"
PREFIX="seed-${RUN_ID}"

# 计数器：按进程号隔离，记录成功/失败条数
SEED_COUNTER="/tmp/seed_counter_$$.txt"
echo "0 0" > "$SEED_COUNTER"

send() {
  # 用 python 显式 UTF-8 发送，绕过 curl 在 Windows 下把命令行中文按 GBK 编码导致 400 的问题
  local SID="$1" MSG="$2"
  local PY
  PY="$(command -v python3 2>/dev/null || command -v python 2>/dev/null || echo python3)"
  local OUT rc
  OUT="$(SID="$SID" MSG="$MSG" "$PY" - <<'PYEOF'
import os, json, sys, urllib.request, urllib.error
sid = os.environ["SID"]
msg = os.environ["MSG"]
url = "http://127.0.0.1:8080/api/bank/chat?sessionId=" + sid
body = json.dumps({"message": msg}).encode("utf-8")
req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json; charset=utf-8"}, method="POST")
try:
    with urllib.request.urlopen(req, timeout=60) as r:
        d = json.loads(r.read().decode("utf-8"))
        status = d.get("status") or "?"
        intent = d.get("intent") or "-"
        answer = d.get("content") or d.get("question") or d.get("errorMessage") or ""
        print("  ✅ [{}] {} — {}".format(status, intent, answer[:60].replace(chr(10), " ")))
        sys.exit(0)
except urllib.error.HTTPError as e:
    try:
        b = e.read().decode("utf-8", "replace")
    except Exception:
        b = ""
    print("  ❌ HTTP {} — {}".format(e.code, b[:120].replace(chr(10), " ")))
    sys.exit(1)
except Exception as e:
    print("  ❌ ERR — {}".format(str(e)[:120]))
    sys.exit(1)
PYEOF
)"
  rc=$?
  echo "$OUT"
  local ok fail
  read ok fail < "$SEED_COUNTER" 2>/dev/null || { ok=0; fail=0; }
  if [ "$rc" -eq 0 ]; then ok=$((ok + 1)); else fail=$((fail + 1)); fi
  echo "$ok $fail" > "$SEED_COUNTER"
}

echo "=========================================="
echo "  手机银行 AI — 数据播种"
echo "  RUN_ID=$RUN_ID  (session 前缀: ${PREFIX}-*)"
echo "=========================================="

echo ""
echo ">>> zhangsan-01: 转账 (3轮)"
send "${PREFIX}-zs1" "转账50000给李四"
sleep 3; send "${PREFIX}-zs1" "确认"
sleep 3; send "${PREFIX}-zs1" "好的，继续"
sleep 3

echo ""
echo ">>> zhangsan-02: 账单查询 (2轮)"
send "${PREFIX}-zs2" "查一下上个月的账单"
sleep 3; send "${PREFIX}-zs2" "餐饮花了多少钱"
sleep 3

echo ""
echo ">>> wangwu-01: 理财全链路 (5轮含REROUTE)"
send "${PREFIX}-ww1" "推荐稳健的基金"
sleep 4; send "${PREFIX}-ww1" "解释一下朝朝盈"
sleep 4; send "${PREFIX}-ww1" "还有别的吗"
sleep 4; send "${PREFIX}-ww1" "转账3万给赵六"
sleep 4; send "${PREFIX}-ww1" "确认"
sleep 4

echo ""
echo ">>> xiaomei-01: 意图消歧 (2轮)"
send "${PREFIX}-xm1" "我想看看理财"
sleep 4; send "${PREFIX}-xm1" "产品解读"
sleep 4

echo ""
echo ">>> laoliu-01: 取消转账 (2轮)"
send "${PREFIX}-ll1" "转账500给张三"
sleep 3; send "${PREFIX}-ll1" "取消"
sleep 3

echo ""
echo ">>> aming-01: 激进理财 (3轮)"
send "${PREFIX}-am1" "推荐科技板块的基金"
sleep 4; send "${PREFIX}-am1" "历史收益"
sleep 4; send "${PREFIX}-am1" "风险等级"
sleep 4

echo ""
echo ">>> xiaoming-01: 闲聊 (2轮)"
send "${PREFIX}-xmi1" "你好"
sleep 3; send "${PREFIX}-xmi1" "我的工资到账了吗"
sleep 3

echo ""
echo "=========================================="
echo "完成！7个会话，19轮"
echo "  sessionId 前缀: ${PREFIX}-* (每次运行随机，重播不叠加旧会话)"
echo "=========================================="

# ── 播种结果汇总（存在失败则以非零码退出，便于自动化感知）──
read ok fail < "$SEED_COUNTER" 2>/dev/null || { ok=0; fail=0; }
rm -f "$SEED_COUNTER"
echo ""
echo ">>> 播种结果：$ok 成功 / $fail 失败"
if [ "$fail" -gt 0 ]; then
  echo "⚠️ 存在失败请求，请检查上方 ❌ 行（后端未启动 / 接口异常 / 网络问题）"
  exit 1
fi
