#!/usr/bin/env python3
"""Direct seed script — bypass broken SessionBridge, POST sessions straight to Backend."""

import json, time, urllib.request, sys

BASE = "http://127.0.0.1:9090/api/v1/sessions"

def post_session(session_id, user_input, ai_response, intent, agent_path, 
                 confidence=0.90, duration_ms=1200, tokens=80, status="COMPLETED"):
    payload = {
        "sessionId": session_id,
        "userId": session_id.split("-")[0] if "-" in session_id else session_id,
        "userInput": user_input,
        "aiResponse": ai_response,
        "intent": intent,
        "agentPath": agent_path,
        "confidence": confidence,
        "durationMs": duration_ms,
        "tokens": tokens,
        "traceId": f"direct-{session_id}",
        "status": status
    }
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(BASE, data=data, headers={"Content-Type": "application/json"})
    try:
        resp = urllib.request.urlopen(req, timeout=5)
        body = json.loads(resp.read())
        ok = body.get("code") == 0
        print(f"  {'OK' if ok else 'FAIL'} [{intent}] {session_id}: {user_input[:40]}...")
        return ok
    except Exception as e:
        print(f"  ERR [{intent}] {session_id}: {e}")
        return False

print("=" * 50)
print("  Direct Seed — bypassing SessionBridge")
print("=" * 50)

total, ok = 0, 0

# ===== Seed scenarios from seed.sh =====

print("\n>>> zhangsan-01: Transfer (3 turns)")
if post_session("zs1", "transfer 50000 to Li Si", "转账请求已收到，请确认50000元转账至Li Si账户", 
                "TRANSFER", "L0→TRANSFER→TransferService"): ok += 1; total += 1
time.sleep(0.3)
if post_session("zs1", "yes confirm", "已确认转账50000元至Li Si账户，预计2小时内到账",
                "TRANSFER", "L0→TRANSFER→TransferService"): ok += 1; total += 1
time.sleep(0.3)
if post_session("zs1", "ok proceed", "转账已完成，50000元已成功转至Li Si账户",
                "TRANSFER", "L0→TRANSFER→TransferService"): ok += 1; total += 1
time.sleep(0.3)

print("\n>>> zhangsan-02: Bill query (2 turns)")
if post_session("zs2", "show last month bills", "上月账单共6笔消费，总额12,580元。餐饮占比最高为35%",
                "BILL_QUERY", "L0→BILL_QUERY→BillService"): ok += 1; total += 1
time.sleep(0.3)
if post_session("zs2", "how much on food", "上月餐饮消费共4,403元，占总消费35%",
                "BILL_QUERY", "L0→BILL_QUERY→BillService"): ok += 1; total += 1
time.sleep(0.3)

print("\n>>> wangwu-01: Wealth full chain (5 turns)")
if post_session("ww1", "recommend conservative funds", "为您推荐3款稳健型基金：华夏纯债A、易方达稳健收益B、招商安心收益C",
                "WEALTH", "L0→WEALTH→FundService"): ok += 1; total += 1
time.sleep(0.3)
if post_session("ww1", "explain Chaochao Ying", "超超盈是招商银行推出的活期理财产品，7日年化收益率2.1%，支持随存随取",
                "WEALTH", "L0→WEALTH→ProductService"): ok += 1; total += 1
time.sleep(0.3)
if post_session("ww1", "anything else", "我们还推荐日日盈、月月盈等系列产品，您可以根据资金使用计划选择",
                "WEALTH", "L0→WEALTH→ProductService"): ok += 1; total += 1
time.sleep(0.3)
if post_session("ww1", "transfer 30000 to Zhao Liu", "转账请求已收到，请确认30000元转账至赵六账户",
                "TRANSFER", "L0→TRANSFER→TransferService"): ok += 1; total += 1
time.sleep(0.3)
if post_session("ww1", "confirm", "已确认转账30000元至赵六账户，交易处理中",
                "TRANSFER", "L0→TRANSFER→TransferService"): ok += 1; total += 1
time.sleep(0.3)

print("\n>>> xiaomei-01: Disambiguation (2 turns)")
if post_session("xm1", "I want to check wealth", "您好，关于财富管理，您是想咨询理财产品还是进行账户资产分析？",
                "WEALTH", "L0→WEALTH→Disambiguation"): ok += 1; total += 1
time.sleep(0.3)
if post_session("xm1", "product interpretation", "为您解读当前热门产品：超超盈7日年化2.1%、稳稳盈近1年收益3.5%",
                "WEALTH", "L0→WEALTH→ProductService"): ok += 1; total += 1
time.sleep(0.3)

print("\n>>> laoliu-01: Cancel transfer (2 turns)")
if post_session("ll1", "transfer 500 to Zhang San", "转账请求已收到，请确认500元转账至张三账户",
                "TRANSFER", "L0→TRANSFER→TransferService"): ok += 1; total += 1
time.sleep(0.3)
if post_session("ll1", "cancel it", "已取消该笔500元转账至张三的请求，资金未扣除",
                "TRANSFER", "L0→TRANSFER→TransferService"): ok += 1; total += 1
time.sleep(0.3)

print("\n>>> aming-01: Aggressive wealth (3 turns)")
if post_session("am1", "recommend tech sector funds", "科技板块基金推荐：景顺长城科技驱动、中欧电子信息、富国科技50",
                "WEALTH", "L0→WEALTH→FundService"): ok += 1; total += 1
time.sleep(0.3)
if post_session("am1", "historical returns", "景顺长城科技驱动近1年收益18.5%，近3年收益45.2%，夏普比率1.2",
                "WEALTH", "L0→WEALTH→FundService"): ok += 1; total += 1
time.sleep(0.3)
if post_session("am1", "risk level", "科技板块基金属于中高风险(R4)，建议仓位不超过总资产的30%",
                "WEALTH", "L0→WEALTH→RiskAnalysis"): ok += 1; total += 1
time.sleep(0.3)

print("\n>>> xiaoming-01: Chat (2 turns)")
if post_session("xmi1", "hello there", "您好！我是您的智能银行助手，有什么可以帮您的吗？",
                "CHAT", "L0→CHAT"): ok += 1; total += 1
time.sleep(0.3)
if post_session("xmi1", "did my salary arrive", "检测到您本月工资已于6月15日到账，金额为18,500元",
                "BILL_QUERY", "L0→BILL_QUERY→BillService"): ok += 1; total += 1
time.sleep(0.3)

# ===== Seed scenarios from seed_new.sh =====

print("\n>>> new_user_01: Fund recommendation (3 turns)")
if post_session("np1", "你好，我想了解一下基金产品，有什么推荐的吗", "为您推荐热门基金：华夏纯债A(稳健)、易方达蓝筹精选(均衡)、天弘沪深300(指数)",
                "WEALTH", "L0→WEALTH→FundService"): ok += 1; total += 1
time.sleep(0.3)
if post_session("np1", "能详细说说货币基金和债券基金的区别吗", "货币基金投资短期货币工具，流动性高，年化1.5-2.5%；债券基金投资债券，收益略高但波动略大，年化3-5%",
                "WEALTH", "L0→WEALTH→ProductService"): ok += 1; total += 1
time.sleep(0.3)
if post_session("np1", "那我先买5000块的货币基金试试", "已为您申购华夏现金增利货币基金5000元，确认份额后次日计算收益",
                "WEALTH", "L0→WEALTH→FundService"): ok += 1; total += 1
time.sleep(0.3)

print("\n>>> transfer_correct_01: Amount correction (4 turns)")
if post_session("np2", "转账500到张三的工商银行账户", "已识别：转账500元至张三(工商银行尾号6789)，请确认",
                "TRANSFER", "L0→TRANSFER→TransferService"): ok += 1; total += 1
time.sleep(0.3)
if post_session("np2", "等等，不是500，改成转1000", "已修改转账金额为1000元至张三(工商银行尾号6789)，请确认",
                "TRANSFER", "L0→TRANSFER→TransferService"): ok += 1; total += 1
time.sleep(0.3)
if post_session("np2", "对，就是1000，确认转账", "已确认，1000元正在转至张三账户，预计30分钟内到账",
                "TRANSFER", "L0→TRANSFER→TransferService"): ok += 1; total += 1
time.sleep(0.3)
if post_session("np2", "好的，帮我查一下转账进度", "转账1000元至张三：银行处理中，预计还需15分钟",
                "TRANSFER", "L0→TRANSFER→TransferService"): ok += 1; total += 1
time.sleep(0.3)

print("\n>>> bill_query_01: Date range bills (3 turns)")
if post_session("np3", "帮我查一下这个月的账单", "本月账单共8笔消费，总额9,320元，消费趋势环比上升12%",
                "BILL_QUERY", "L0→BILL_QUERY→BillService"): ok += 1; total += 1
time.sleep(0.3)
if post_session("np3", "只看3月1号到3月15号的消费记录", "3月1日至15日共4笔消费：餐饮2笔(560元)、购物1笔(2300元)、交通1笔(120元)",
                "BILL_QUERY", "L0→BILL_QUERY→BillService"): ok += 1; total += 1
time.sleep(0.3)
if post_session("np3", "其中餐饮类的花了多少钱", "3月上半月餐饮消费560元，日均56元，较上月同期下降8%",
                "BILL_QUERY", "L0→BILL_QUERY→BillService"): ok += 1; total += 1
time.sleep(0.3)

print("\n>>> wealth_disambig_01: Wealth disambiguation (3 turns)")
if post_session("np4", "我想了解一下理财", "理财相关功能包括：产品推荐、资产分析、收益测算。请问您想了解哪方面？",
                "WEALTH", "L0→WEALTH→Disambiguation"): ok += 1; total += 1
time.sleep(0.3)
if post_session("np4", "帮我分析一下我现在的资产配置", "您的资产配置：活期存款45%、定期存款20%、基金25%、理财10%。建议增加理财产品占比",
                "WEALTH", "L0→WEALTH→PortfolioAnalysis"): ok += 1; total += 1
time.sleep(0.3)
if post_session("np4", "那推荐一些稳健型的理财产品吧", "稳健型推荐：日日盈(7日年化2.0%)、月月盈(近1月收益3.2%)、季季盈(近1季收益4.1%)",
                "WEALTH", "L0→WEALTH→ProductService"): ok += 1; total += 1
time.sleep(0.3)

print("\n>>> chat_to_wealth_01: Chat->Wealth (4 turns)")
if post_session("np5", "嗨，早上好", "早上好！祝您今天心情愉快。需要我帮您查询什么吗？",
                "CHAT", "L0→CHAT"): ok += 1; total += 1
time.sleep(0.3)
if post_session("np5", "今天心情不错，想看看有什么好的理财产品", "心情好是投资的好时机！为您精选：超超盈(灵活存取)、稳稳盈(稳健增长)、长远盈(长期规划)",
                "WEALTH", "L0→WEALTH→ProductService"): ok += 1; total += 1
time.sleep(0.3)
if post_session("np5", "我风险承受能力一般，推荐什么类型的基金", "建议配置平衡混合型基金(股债50:50)，推荐：交银施罗德定期支付、兴全可转债混合",
                "WEALTH", "L0→WEALTH→FundService"): ok += 1; total += 1
time.sleep(0.3)
if post_session("np5", "谢谢，那就先关注一下混合型基金", "已为您关注上述混合型基金，涨跌提醒已开启。祝您投资顺利！",
                "WEALTH", "L0→WEALTH→FundService"): ok += 1; total += 1
time.sleep(0.3)

print(f"\n{'=' * 50}")
print(f"Done! {ok}/{total} posts succeeded")
print("  seed.sh: 7 sessions, 19 turns")
print("  seed_new.sh: 5 sessions, 17 turns")
print(f"{'=' * 50}")

sys.exit(0 if ok == total else 1)
