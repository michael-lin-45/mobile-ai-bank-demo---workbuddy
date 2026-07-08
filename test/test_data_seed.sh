#!/bin/bash
# ==================================================
# 手机银行 AI 助手 — 测试数据播种脚本
# 用途：每次测试前执行，生成多轮对话数据填充可观测系统
# 用法：bash test_data_seed.sh
# 前提：手机银行主应用已在 8080 端口启动
# ==================================================
set -e

BASE_URL="http://127.0.0.1:8080/api/bank/chat"

echo "=========================================="
echo "  手机银行 AI 可观测数据播种"
echo "=========================================="
echo ""

# ==================== 用户 1：张三（转账场景） ====================
echo ">>> 用户1: 张三 — 转账 + 追问"

# Turn 1 — 开始转账
echo "  [张三] Turn 1: 我要转账"
curl -s -X POST "$BASE_URL?sessionId=zhangsan-01" \
  -H 'Content-Type: application/json' \
  -d '{"query":"帮我转50000给李四，用途是房租"}' | jq -r '.answer' 2>/dev/null || echo "  (请求已发送)"
sleep 2

# Turn 2 — 追问/确认收款人
echo "  [张三] Turn 2: 确定，就是李四"
curl -s -X POST "$BASE_URL?sessionId=zhangsan-01" \
  -H 'Content-Type: application/json' \
  -d '{"query":"确定，就是他"}' | jq -r '.answer' 2>/dev/null || echo "  (请求已发送)"
sleep 2

# Turn 3 — 转账完成
echo "  [张三] Turn 3: 确认转账"
curl -s -X POST "$BASE_URL?sessionId=zhangsan-01" \
  -H 'Content-Type: application/json' \
  -d '{"query":"好的，确认转账"}' | jq -r '.answer' 2>/dev/null || echo "  (请求已发送)"
sleep 2

echo "  ✅ zhangsan-01 完成"
echo ""

# ==================== 用户 1：张三（账单查询） ====================
echo ">>> 用户1: 张三 — 账单查询"

# Turn 1 — 查账单
echo "  [张三] Turn 1: 查上月账单"
curl -s -X POST "$BASE_URL?sessionId=zhangsan-02" \
  -H 'Content-Type: application/json' \
  -d '{"query":"帮我查一下上个月的账单，看看花了多少钱"}' | jq -r '.answer' 2>/dev/null || echo "  (请求已发送)"
sleep 2

# Turn 2 — 追问明细
echo "  [张三] Turn 2: 看餐饮支出"
curl -s -X POST "$BASE_URL?sessionId=zhangsan-02" \
  -H 'Content-Type: application/json' \
  -d '{"query":"那餐饮支出多少？"}' | jq -r '.answer' 2>/dev/null || echo "  (请求已发送)"
sleep 2

echo "  ✅ zhangsan-02 完成"
echo ""

# ==================== 用户 2：王五（理财咨询） ====================
echo ">>> 用户2: 王五 — 理财咨询 + 产品解读 + 跨域REROUTE"

# Turn 1 — 理财推荐
echo "  [王五] Turn 1: 推荐理财产品"
curl -s -X POST "$BASE_URL?sessionId=wangwu-01" \
  -H 'Content-Type: application/json' \
  -d '{"query":"我比较保守，有没有稳健的理财产品推荐？"}' | jq -r '.answer' 2>/dev/null || echo "  (请求已发送)"
sleep 2

# Turn 2 — 解读具体产品
echo "  [王五] Turn 2: 解读朝朝盈"
curl -s -X POST "$BASE_URL?sessionId=wangwu-01" \
  -H 'Content-Type: application/json' \
  -d '{"query":"帮我解读一下朝朝盈这个产品"}' | jq -r '.answer' 2>/dev/null || echo "  (请求已发送)"
sleep 2

# Turn 3 — 切回推荐
echo "  [王五] Turn 3: 继续推荐"
curl -s -X POST "$BASE_URL?sessionId=wangwu-01" \
  -H 'Content-Type: application/json' \
  -d '{"query":"除了朝朝盈还有什么？"}' | jq -r '.answer' 2>/dev/null || echo "  (请求已发送)"
sleep 2

# Turn 4 — 突然想转账（跨域 REROUTE）
echo "  [王五] Turn 4: 跨域转账（触发REROUTE）"
curl -s -X POST "$BASE_URL?sessionId=wangwu-01" \
  -H 'Content-Type: application/json' \
  -d '{"query":"算了，帮我转3万给赵六"}' | jq -r '.answer' 2>/dev/null || echo "  (请求已发送)"
sleep 2

# Turn 5 — 完成转账
echo "  [王五] Turn 5: 确认转账"
curl -s -X POST "$BASE_URL?sessionId=wangwu-01" \
  -H 'Content-Type: application/json' \
  -d '{"query":"确认"}' | jq -r '.answer' 2>/dev/null || echo "  (请求已发送)"
sleep 2

echo "  ✅ wangwu-01 完成（含跨域REROUTE）"
echo ""

# ==================== 用户 3：小美（消歧场景） ====================
echo ">>> 用户3: 小美 — 意图消歧"

# Turn 1 — 模糊理财请求（触发消歧）
echo "  [小美] Turn 1: 模糊请求"
curl -s -X POST "$BASE_URL?sessionId=xiaomei-01" \
  -H 'Content-Type: application/json' \
  -d '{"query":"我想看看理财"}' | jq -r '.answer' 2>/dev/null || echo "  (请求已发送)"
sleep 2

# Turn 2 — 用户回应消歧 — 选择产品解读
echo "  [小美] Turn 2: 选产品解读"
curl -s -X POST "$BASE_URL?sessionId=xiaomei-01" \
  -H 'Content-Type: application/json' \
  -d '{"query":"产品解读"}' | jq -r '.answer' 2>/dev/null || echo "  (请求已发送)"
sleep 2

echo "  ✅ xiaomei-01 完成（含消歧）"
echo ""

# ==================== 用户 4：老刘（取消转账） ====================
echo ">>> 用户4: 老刘 — 转账后取消"

curl -s -X POST "$BASE_URL?sessionId=laoliu-01" \
  -H 'Content-Type: application/json' \
  -d '{"query":"转500给张三"}' | jq -r '.answer' 2>/dev/null || echo "  (请求已发送)"
sleep 2

echo "  [老刘] Turn 2: 取消"
curl -s -X POST "$BASE_URL?sessionId=laoliu-01" \
  -H 'Content-Type: application/json' \
  -d '{"query":"算了不转了，取消"}' | jq -r '.answer' 2>/dev/null || echo "  (请求已发送)"
sleep 2

echo "  ✅ laoliu-01 完成（含取消）"
echo ""

# ==================== 用户 5：阿明（多轮追问） ====================
echo ">>> 用户5: 阿明 — 激进型理财 + 多轮追问"

curl -s -X POST "$BASE_URL?sessionId=aming-01" \
  -H 'Content-Type: application/json' \
  -d '{"query":"我是激进型投资者，推荐几只科技领域的基金"}' | jq -r '.answer' 2>/dev/null || echo "  (请求已发送)"
sleep 2

echo "  [阿明] Turn 2: 追问"
curl -s -X POST "$BASE_URL?sessionId=aming-01" \
  -H 'Content-Type: application/json' \
  -d '{"query":"这几只基金历史收益怎么样"}' | jq -r '.answer' 2>/dev/null || echo "  (请求已发送)"
sleep 2

echo "  [阿明] Turn 3: 再追问"
curl -s -X POST "$BASE_URL?sessionId=aming-01" \
  -H 'Content-Type: application/json' \
  -d '{"query":"风险等级呢"}' | jq -r '.answer' 2>/dev/null || echo "  (请求已发送)"
sleep 2

echo "  ✅ aming-01 完成（含多轮追问）"
echo ""

# ==================== 用户 6：小明（简单闲聊） ====================
echo ">>> 用户6: 小明 — 闲聊"

curl -s -X POST "$BASE_URL?sessionId=xiaoming-01" \
  -H 'Content-Type: application/json' \
  -d '{"query":"你好，今天天气不错"}' | jq -r '.answer' 2>/dev/null || echo "  (请求已发送)"
sleep 2

echo "  [小明] Turn 2: 闲聊然后问业务"
curl -s -X POST "$BASE_URL?sessionId=xiaoming-01" \
  -H 'Content-Type: application/json' \
  -d '{"query":"对了，我的工资到账了吗"}' | jq -r '.answer' 2>/dev/null || echo "  (请求已发送)"
sleep 2

echo "  ✅ xiaoming-01 完成"
echo ""

# ==================== 统计 ====================
echo "=========================================="
echo "  🌱 数据播种完成！"
echo ""
echo "  共生成 6 个 session："
echo "    zhangsan-01 : 转账（3轮 + 追问确认）"
echo "    zhangsan-02 : 账单查询（2轮 + 追问明细）"
echo "    wangwu-01   : 理财咨询→解读→推荐→转账（5轮含REROUTE）"
echo "    xiaomei-01  : 意图消歧（2轮含WEALTH组消歧）"
echo "    laoliu-01   : 转账取消（2轮含取消检测）"
echo "    aming-01    : 激进理财（3轮含追问）"
echo "    xiaoming-01 : 闲聊→账单（2轮含域切换）"
echo ""
echo "  覆盖场景：转账/账单/理财咨询/产品解读/消歧/REROUTE/取消/追问/闲聊"
echo "  覆盖 Agent：L0/L1-LLM1/L1-LLM2/L2×4(Transfer/Bill/Consult/Interpret)"
echo "=========================================="
