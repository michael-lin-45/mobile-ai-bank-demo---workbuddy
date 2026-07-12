#!/bin/bash
# Seed script v2 — correct field name "message"
# session_id 随机化: 每次运行生成独立 RUN 前缀, 避免重播叠加旧会话 (fix #3 污染)
BASE="http://127.0.0.1:8080/api/bank/chat"

# 每轮播种使用随机 run 前缀（epoch 秒 + $RANDOM），保证重播不会追加到旧会话
RUN_ID="s$(date +%s)${RANDOM}"
PREFIX="seed-${RUN_ID}"

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
echo "  Mobile AI Bank — Data Seed"
echo "  RUN_ID=$RUN_ID  (session 前缀: ${PREFIX}-*)"
echo "=========================================="

echo ""
echo ">>> zhangsan-01: Transfer (3 turns)"
send "${PREFIX}-zs1" "transfer 50000 to Li Si"
sleep 3; send "${PREFIX}-zs1" "yes confirm"
sleep 3; send "${PREFIX}-zs1" "ok proceed"
sleep 3

echo ""
echo ">>> zhangsan-02: Bill query (2 turns)"
send "${PREFIX}-zs2" "show last month bills"
sleep 3; send "${PREFIX}-zs2" "how much on food"
sleep 3

echo ""
echo ">>> wangwu-01: Wealth full chain (5 turns inc REROUTE)"
send "${PREFIX}-ww1" "recommend conservative funds"
sleep 4; send "${PREFIX}-ww1" "explain Chaochao Ying"
sleep 4; send "${PREFIX}-ww1" "anything else"
sleep 4; send "${PREFIX}-ww1" "transfer 30000 to Zhao Liu"
sleep 4; send "${PREFIX}-ww1" "confirm"
sleep 4

echo ""
echo ">>> xiaomei-01: Disambiguation (2 turns)"
send "${PREFIX}-xm1" "I want to check wealth"
sleep 4; send "${PREFIX}-xm1" "product interpretation"
sleep 4

echo ""
echo ">>> laoliu-01: Cancel transfer (2 turns)"
send "${PREFIX}-ll1" "transfer 500 to Zhang San"
sleep 3; send "${PREFIX}-ll1" "cancel it"
sleep 3

echo ""
echo ">>> aming-01: Aggressive wealth (3 turns)"
send "${PREFIX}-am1" "recommend tech sector funds"
sleep 4; send "${PREFIX}-am1" "historical returns"
sleep 4; send "${PREFIX}-am1" "risk level"
sleep 4

echo ""
echo ">>> xiaoming-01: Chat (2 turns)"
send "${PREFIX}-xmi1" "hello there"
sleep 3; send "${PREFIX}-xmi1" "did my salary arrive"
sleep 3

echo ""
echo "=========================================="
echo "Done! 7 sessions, 19 turns"
echo "  sessionId 前缀: ${PREFIX}-* (每次运行随机，重播不叠加旧会话)"
echo "=========================================="
