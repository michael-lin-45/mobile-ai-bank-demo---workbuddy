#!/bin/bash
# Seed script v3 — 增量新对话数据
# 用于补充 test/seed.sh 未覆盖的场景
# 目标 URL 使用 127.0.0.1（Windows DNS 兼容）
BASE="http://127.0.0.1:8080/api/bank/chat"

send() {
  curl -s -X POST "$BASE?sessionId=$1" -H 'Content-Type: application/json' \
    -d "{\"message\":\"$2\"}" 2>&1 | python3 -c "
import sys,json
try:
  d=json.load(sys.stdin)
  print(f'  [{d[\"status\"]}] {d.get(\"intent\",\"?\")} - {d.get(\"content\",\"\")[:40]}')
except: print('  (sent)')
" 2>/dev/null
}

echo "=========================================="
echo "  Mobile AI Bank — Incremental Seed v3"
echo "=========================================="

# ============================================================
# Scenario 1: new_user_01 — 新用户咨询基金推荐 (3 turns)
# 覆盖: 基金推荐 → 追问详情 → 决定购买
# ============================================================
echo ""
echo ">>> new_user_01: 基金推荐 (3 turns)"
echo "    场景: 新用户初次使用，咨询基金产品推荐"
send "np1" "你好，我想了解一下基金产品，有什么推荐的吗"
sleep 4
send "np1" "能详细说说货币基金和债券基金的区别吗"
sleep 4
send "np1" "那我先买5000块的货币基金试试"
sleep 5

# ============================================================
# Scenario 2: transfer_correct_01 — 多轮转账+金额修正 (4 turns)
# 覆盖: 发起转账 → 改口修正金额 → 确认 → 完成
# ============================================================
echo ""
echo ">>> transfer_correct_01: 转账金额修正 (4 turns)"
echo "    场景: 用户发起转账后改口修正金额"
send "np2" "转账500到张三的工商银行账户"
sleep 3
send "np2" "等等，不是500，改成转1000"
sleep 3
send "np2" "对，就是1000，确认转账"
sleep 3
send "np2" "好的，帮我查一下转账进度"
sleep 5

# ============================================================
# Scenario 3: bill_query_01 — 账单查询+指定日期范围 (3 turns)
# 覆盖: 账单查询 → 指定日期范围筛选 → 按类别查看
# ============================================================
echo ""
echo ">>> bill_query_01: 指定日期范围账单查询 (3 turns)"
echo "    场景: 用户查询特定时间段的账单明细"
send "np3" "帮我查一下这个月的账单"
sleep 4
send "np3" "只看3月1号到3月15号的消费记录"
sleep 4
send "np3" "其中餐饮类的花了多少钱"
sleep 5

# ============================================================
# Scenario 4: wealth_disambig_01 — 理财消歧 (3 turns)
# 覆盖: 模糊"理财" → 系统消歧 → 用户选择后进入子场景
# ============================================================
echo ""
echo ">>> wealth_disambig_01: 理财消歧 (3 turns)"
echo "    场景: 用户说"理财"，系统消歧：咨询还是产品解读"
send "np4" "我想了解一下理财"
sleep 4
send "np4" "帮我分析一下我现在的资产配置"
sleep 4
send "np4" "那推荐一些稳健型的理财产品吧"
sleep 5

# ============================================================
# Scenario 5: chat_to_wealth_01 — 闲聊转财富请求 (4 turns)
# 覆盖: 闲聊 → 问候 → 突然切换业务 → 基金推荐
# ============================================================
echo ""
echo ">>> chat_to_wealth_01: 闲聊→财富请求 (4 turns)"
echo "    场景: 用户先闲聊打招呼，然后突然切入理财咨询"
send "np5" "嗨，早上好"
sleep 3
send "np5" "今天心情不错，想看看有什么好的理财产品"
sleep 4
send "np5" "我风险承受能力一般，推荐什么类型的基金"
sleep 4
send "np5" "谢谢，那就先关注一下混合型基金"
sleep 3

echo ""
echo "=========================================="
echo "Done! 5 sessions, 17 turns"
echo "  np1: 基金推荐 (3 turns)"
echo "  np2: 转账金额修正 (4 turns)"
echo "  np3: 日期范围账单查询 (3 turns)"
echo "  np4: 理财消歧 (3 turns)"
echo "  np5: 闲聊→财富请求 (4 turns)"
echo "=========================================="
