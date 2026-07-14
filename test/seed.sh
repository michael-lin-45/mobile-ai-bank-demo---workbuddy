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
