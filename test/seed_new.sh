#!/bin/bash
# Seed script v3 — 增量新对话数据
# 用于补充 test/seed.sh 未覆盖的场景
# 目标 URL 使用 127.0.0.1（Windows DNS 兼容）
# session_id 随机化: 每次运行生成独立 RUN 前缀, 避免重播叠加旧会话 (fix #3 污染)
BASE="http://127.0.0.1:8080/api/bank/chat"

# 每轮播种使用随机 run 前缀（epoch 秒 + $RANDOM），保证重播不会追加到旧会话
RUN_ID="s$(date +%s)${RANDOM}"
PREFIX="seed-${RUN_ID}"

send() {
  # 用 python 显式 UTF-8 发送，绕过 curl 在 Windows 下把命令行中文按 GBK 编码导致 400 的问题
  SID="$1" MSG="$2" python3 - <<'PYEOF'
import os, json, urllib.request, urllib.error
sid = os.environ["SID"]
msg = os.environ["MSG"]
url = "http://127.0.0.1:8080/api/bank/chat?sessionId=" + sid
body = json.dumps({"message": msg}).encode("utf-8")
req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json; charset=utf-8"}, method="POST")
try:
    with urllib.request.urlopen(req, timeout=60) as r:
        d = json.loads(r.read().decode("utf-8"))
        print(f"  [{d.get('status')}] {d.get('intent','?')} - {(d.get('content') or d.get('question') or '')[:40]}")
except urllib.error.HTTPError as e:
    print(f"  [HTTP {e.code}] {e.read().decode('utf-8','replace')[:120]}")
except Exception as e:
    print(f"  [ERR] {e}")
PYEOF
}

echo "=========================================="
echo "  Mobile AI Bank — Incremental Seed v3"
echo "  RUN_ID=$RUN_ID  (session 前缀: ${PREFIX}-*)"
echo "=========================================="

# ============================================================
# Scenario 1: new_user_01 — 新用户咨询基金推荐 (3 turns)
# 覆盖: 基金推荐 → 追问详情 → 决定购买
# ============================================================
echo ""
echo ">>> new_user_01: 基金推荐 (3 turns)"
echo "    场景: 新用户初次使用，咨询基金产品推荐"
send "${PREFIX}-np1" "你好，我想了解一下基金产品，有什么推荐的吗"
sleep 4
send "${PREFIX}-np1" "能详细说说货币基金和债券基金的区别吗"
sleep 4
send "${PREFIX}-np1" "那我先买5000块的货币基金试试"
sleep 5

# ============================================================
# Scenario 2: transfer_correct_01 — 多轮转账+金额修正 (4 turns)
# 覆盖: 发起转账 → 改口修正金额 → 确认 → 完成
# ============================================================
echo ""
echo ">>> transfer_correct_01: 转账金额修正 (4 turns)"
echo "    场景: 用户发起转账后改口修正金额"
send "${PREFIX}-np2" "转账500到张三的工商银行账户"
sleep 3
send "${PREFIX}-np2" "等等，不是500，改成转1000"
sleep 3
send "${PREFIX}-np2" "对，就是1000，确认转账"
sleep 3
send "${PREFIX}-np2" "好的，帮我查一下转账进度"
sleep 5

# ============================================================
# Scenario 3: bill_query_01 — 账单查询+指定日期范围 (3 turns)
# 覆盖: 账单查询 → 指定日期范围筛选 → 按类别查看
# ============================================================
echo ""
echo ">>> bill_query_01: 指定日期范围账单查询 (3 turns)"
echo "    场景: 用户查询特定时间段的账单明细"
send "${PREFIX}-np3" "帮我查一下这个月的账单"
sleep 4
send "${PREFIX}-np3" "只看3月1号到3月15号的消费记录"
sleep 4
send "${PREFIX}-np3" "其中餐饮类的花了多少钱"
sleep 5

# ============================================================
# Scenario 4: wealth_disambig_01 — 理财消歧 (3 turns)
# 覆盖: 模糊"理财" → 系统消歧 → 用户选择后进入子场景
# ============================================================
echo ""
echo ">>> wealth_disambig_01: 理财消歧 (3 turns)"
echo "    场景: 用户说"理财"，系统消歧：咨询还是产品解读"
send "${PREFIX}-np4" "我想了解一下理财"
sleep 4
send "${PREFIX}-np4" "帮我分析一下我现在的资产配置"
sleep 4
send "${PREFIX}-np4" "那推荐一些稳健型的理财产品吧"
sleep 5

# ============================================================
# Scenario 5: chat_to_wealth_01 — 闲聊转财富请求 (4 turns)
# 覆盖: 闲聊 → 问候 → 突然切换业务 → 基金推荐
# ============================================================
echo ""
echo ">>> chat_to_wealth_01: 闲聊→财富请求 (4 turns)"
echo "    场景: 用户先闲聊打招呼，然后突然切入理财咨询"
send "${PREFIX}-np5" "嗨，早上好"
sleep 3
send "${PREFIX}-np5" "今天心情不错，想看看有什么好的理财产品"
sleep 4
send "${PREFIX}-np5" "我风险承受能力一般，推荐什么类型的基金"
sleep 4
send "${PREFIX}-np5" "谢谢，那就先关注一下混合型基金"
sleep 3

echo ""
echo "=========================================="
echo "Done! 5 sessions, 17 turns"
echo "  np1: 基金推荐 (3 turns)"
echo "  np2: 转账金额修正 (4 turns)"
echo "  np3: 日期范围账单查询 (3 turns)"
echo "  np4: 理财消歧 (3 turns)"
echo "  np5: 闲聊→财富请求 (4 turns)"
echo "  sessionId 前缀: ${PREFIX}-* (每次运行随机，重播不叠加旧会话)"
echo "=========================================="
