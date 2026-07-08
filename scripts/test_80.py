#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""L0->L1->L2 comprehensive test suite - Round 3 with corner cases"""
import requests, json, sys, time, io

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')

BASE = "http://localhost:8080/api/bank"
pass_count = 0
fail_count = 0
results_detail = []

def chat(sid, msg):
    try:
        r = requests.post(f"{BASE}/chat?sessionId={sid}", json={"message": msg}, timeout=60)
        return r.json()
    except Exception as e:
        return {"status": "ERROR", "errorMessage": str(e)}

def clear(sid):
    try:
        requests.delete(f"{BASE}/session?sessionId={sid}", timeout=10)
    except:
        pass

def check(resp, expected_status=None, expected_contains=None, expected_not_contains=None):
    reasons = []
    ok = True
    if expected_status and resp.get("status") != expected_status:
        ok = False
        reasons.append(f"status={resp.get('status')} expected={expected_status}")
    content = resp.get("content") or resp.get("question") or resp.get("errorMessage") or ""
    if expected_contains:
        for kw in expected_contains.split("|"):
            if kw in content:
                break
        else:
            ok = False
            reasons.append(f"content missing '{expected_contains}': {content[:80]}")
    if expected_not_contains:
        for kw in expected_not_contains.split("|"):
            if kw in content:
                ok = False
                reasons.append(f"content should not contain '{kw}': {content[:80]}")
                break
    return ok, "; ".join(reasons)

def tc(num, desc, messages, **kwargs):
    global pass_count, fail_count
    sid = f"t{num}_{int(time.time()*1000)}"
    resp = None
    for msg in messages:
        resp = chat(sid, msg)
    ok, reason = check(resp, **kwargs)
    if ok:
        pass_count += 1
    else:
        fail_count += 1
    content_preview = (resp.get("content") or resp.get("question") or "N/A")[:100]
    content_preview = content_preview.encode('ascii', errors='replace').decode('ascii')
    tag = "PASS" if ok else "FAIL"
    print(f"[{tag}] #{num} {desc} -> status={resp.get('status','?')} | {content_preview}")
    if not ok:
        print(f"       Reason: {reason}")
    results_detail.append({"num": num, "desc": desc, "tag": tag, "status": resp.get("status","?"), "reason": reason if not ok else ""})
    clear(sid)
    return ok

print("=" * 60)
print(" L0-L1-L2 Comprehensive Test Suite (Round 3)")
print("=" * 60)

# ========== 1. Direct Completion (5) ==========
print("\n--- 1. Direct Completion ---")
tc(1, "transfer-full", ["给张三转账500元"], expected_status="COMPLETED", expected_contains="张三|转账|500")
tc(2, "bill-expense", ["查一下上个月的支出"], expected_status="COMPLETED", expected_contains="支出|账单|消费")
tc(3, "wealth-recommend", ["推荐稳健型理财产品"], expected_status="COMPLETED", expected_contains="理财|推荐|稳健")
tc(4, "wealth-interpret-needs-param", ["解读金享利理财产品"], expected_status="INTERRUPTED", expected_contains="解读|产品")
tc(5, "chat-greeting", ["你好"], expected_status="COMPLETED")

# ========== 2. FOLLOW_UP Multi-turn (7) ==========
print("\n--- 2. FOLLOW_UP Multi-turn ---")
tc(6, "transfer-2turn", ["我要转账", "张三"], expected_status="INTERRUPTED", expected_contains="金额|多少")
tc(7, "transfer-3turn-done", ["我要转账", "张三", "500"], expected_status="COMPLETED", expected_contains="张三|500|成功")
tc(8, "bill-2turn", ["查账单", "上个月"], expected_status="INTERRUPTED", expected_contains="收支|类型|支出")
tc(9, "bill-3turn-done", ["查账单", "上个月", "支出"], expected_status="COMPLETED", expected_contains="支出|账单")
tc(10, "wealth-consult-2turn", ["推荐理财产品", "稳健型"], expected_status="INTERRUPTED", expected_contains="风险|偏好|稳健")
tc(11, "wealth-interpret-2turn", ["解读金享利", "金享利"], expected_status="COMPLETED", expected_contains="金享利")
tc(12, "chat-multi", ["你好", "今天天气怎么样"], expected_status="COMPLETED")

# ========== 3. Intent Switch (8) ==========
print("\n--- 3. Intent Switch ---")
tc(13, "transfer->bill(算了X)", ["我要转账", "算了查一下账单"], expected_status="INTERRUPTED", expected_contains="账单|时间|类型")
tc(14, "bill->transfer(算了X)", ["查账单", "算了我要转账"], expected_status="INTERRUPTED", expected_contains="转给谁|收款|转账")
tc(15, "transfer->chat", ["我要转账", "今天天气不错"], expected_status="COMPLETED")
tc(16, "chat->transfer", ["你好", "我要转账"], expected_status="INTERRUPTED", expected_contains="转给谁|收款")
tc(17, "wealth->transfer(算了X)", ["推荐理财产品", "算了我要转账"], expected_status="INTERRUPTED", expected_contains="转给谁|收款|转账")
tc(18, "transfer->wealth", ["我要转账", "推荐稳健理财"], expected_status="DISAMBIGUATION", expected_contains="咨询|解读")
tc(19, "bill->chat", ["查账单", "讲个笑话"], expected_status="COMPLETED")
tc(20, "chat->bill", ["你好", "查账单"], expected_status="INTERRUPTED", expected_contains="账单|时间")

# ========== 4. RESUME (8) ==========
print("\n--- 4. RESUME ---")
tc(21, "transfer->bill->resume-transfer", ["我要转账", "张三", "算了查账单", "继续转账吧500块"], expected_status="INTERRUPTED", expected_contains="转账|金额|转给谁")
tc(22, "bill->chat->resume-bill", ["查账单", "上个月", "你好", "继续查账单支出"], expected_status="COMPLETED", expected_contains="支出|账单")
tc(23, "wealth->transfer->resume-wealth", ["推荐理财", "稳健型", "算了我要转账", "继续理财咨询吧"], expected_status="INTERRUPTED", expected_contains="风险|偏好|稳健")
tc(24, "transfer->chat->continue-transfer", ["我要转账", "张三", "你好", "转500"], expected_status="COMPLETED", expected_contains="张三|500")
tc(25, "wealth-interpret->transfer->resume-interpret", ["解读金享利", "金享利", "我要转账", "继续解读"], expected_status="INTERRUPTED", expected_contains="解读|产品")  # 可能需再追问产品名
tc(26, "multi-domain-switch-resume", ["我要转账", "查账单", "推荐理财", "继续转账500"], expected_status="INTERRUPTED", expected_contains="转账|金额|转给谁")
tc(27, "resume-with-explicit-intent", ["我要转账", "张三", "查账单", "上个月", "回到转账500元"], expected_status="INTERRUPTED", expected_contains="转账|金额|转给谁")
tc(28, "wealth-consult->interpret->resume-consult", ["推荐稳健理财", "解读金享利", "继续推荐"], expected_status="INTERRUPTED", expected_contains="风险|偏好|稳健|推荐")

# ========== 5. Cancel (7) ==========
print("\n--- 5. Cancel ---")
tc(29, "cancel-transfer", ["我要转账", "取消"], expected_status="COMPLETED", expected_contains="取消|好的")
tc(30, "cancel-bill", ["查账单", "算了"], expected_status="COMPLETED")
tc(31, "cancel-transfer-midway", ["我要转账", "张三", "不转了"], expected_status="COMPLETED", expected_contains="取消|好的")
tc(32, "cancel-disambiguation", ["理财", "取消"], expected_status="COMPLETED")
tc(33, "cancel-then-new", ["我要转账", "取消", "查账单"], expected_status="INTERRUPTED", expected_contains="账单|时间")
tc(34, "abandon-operation", ["我要转账", "放弃"], expected_status="COMPLETED")
tc(35, "stop-query", ["查账单", "不查了"], expected_status="COMPLETED")

# ========== 6. Disambiguation (5) ==========
print("\n--- 6. Disambiguation ---")
tc(36, "wealth-ambiguous", ["理财"], expected_status="DISAMBIGUATION", expected_contains="咨询|解读")
tc(37, "disambig-answer-consult", ["理财", "咨询"], expected_status="INTERRUPTED", expected_contains="风险|偏好|稳健")
tc(38, "disambig-answer-interpret", ["理财", "解读"], expected_status="INTERRUPTED", expected_contains="解读|产品")
tc(39, "explicit-consult-needs-param", ["理财咨询"], expected_status="INTERRUPTED", expected_contains="风险|偏好|稳健")
tc(40, "explicit-interpret-needs-param", ["理财产品解读"], expected_status="INTERRUPTED", expected_contains="解读|产品")

# ========== 7. Edge / UNSUPPORTED (10) ==========
print("\n--- 7. Edge / UNSUPPORTED ---")
tc(41, "loan-unsupported", ["我要申请贷款"], expected_status="COMPLETED", expected_contains="不支持")
tc(42, "credit-card-unsupported", ["信用卡额度查询"], expected_status="COMPLETED", expected_contains="不支持")
tc(43, "promotion-unsupported", ["有什么优惠活动"], expected_status="COMPLETED", expected_contains="不支持")
tc(44, "points-unsupported", ["积分兑换"], expected_status="COMPLETED", expected_contains="不支持")
tc(45, "empty-message", [""], expected_status="ERROR")
tc(46, "wealth-purchase-unsupported", ["购买理财产品"], expected_status="DISAMBIGUATION", expected_contains="咨询|解读")
tc(47, "short-number-followup", ["我要转账", "100"], expected_status="INTERRUPTED", expected_contains="转给谁|收款")
tc(48, "repeat-intent", ["查账单", "再查一次账单"], expected_status="INTERRUPTED", expected_contains="账单|时间")
tc(49, "transfer-then-transfer", ["给李四转200元", "再给王五转300"], expected_status="COMPLETED", expected_contains="王五|300")
tc(50, "vip-benefits-unsupported", ["我的会员权益"], expected_status="COMPLETED", expected_contains="不支持")

# ========== 8. Corner Cases - Context-based L0 routing (15) ==========
print("\n--- 8. Corner Cases - Context-based Routing ---")
tc(51, "不转了+新意图=WEALTH", ["我要转账", "不转了推荐理财"], expected_status="DISAMBIGUATION", expected_contains="咨询|解读")
tc(52, "算了+新意图=TRANSFER", ["查账单", "算了还是转账吧"], expected_status="INTERRUPTED", expected_contains="转给谁|收款|转账")
tc(53, "继续+领域=RESUME-transfer", ["我要转账", "张三", "查账单", "继续转账"], expected_status="INTERRUPTED", expected_contains="转账|金额|转给谁")
tc(54, "回到+领域=RESUME-wealth", ["推荐理财", "我要转账", "回到理财"], expected_status="INTERRUPTED", expected_contains="风险|偏好|稳健|理财")
tc(55, "还是X吧=RESUME", ["我要转账", "张三", "查账单", "还是转账吧500块"], expected_status="INTERRUPTED", expected_contains="转账|金额|转给谁")
tc(56, "cancel-only-no-new-intent", ["我要转账", "算了"], expected_status="COMPLETED", expected_contains="取消|好的")
tc(57, "不查了+新意图=TRANSFER", ["查账单", "不查了我要转账"], expected_status="INTERRUPTED", expected_contains="转给谁|收款|转账")
tc(58, "短答案-金额=TRANSFER-followup", ["我要转账", "张三", "500"], expected_status="COMPLETED", expected_contains="张三|500|成功")
tc(59, "短答案-时间=BILL-followup", ["查账单", "这个月"], expected_status="INTERRUPTED", expected_contains="收支|类型|支出")
tc(60, "bill-income-type", ["查账单", "上个月", "收入"], expected_status="COMPLETED", expected_contains="收入|账单")

# ========== 9. Corner Cases - Multi-domain flow (10) ==========
print("\n--- 9. Corner Cases - Multi-domain Flow ---")
tc(61, "3-domain-pingpong", ["我要转账", "张三", "查账单", "推荐理财", "继续转账500"], expected_status="INTERRUPTED", expected_contains="转账|金额|转给谁")
tc(62, "transfer-complete-then-new", ["给李四转200", "查账单"], expected_status="INTERRUPTED", expected_contains="账单|时间")
tc(63, "bill-complete-then-transfer", ["查上个月支出", "我要转账"], expected_status="INTERRUPTED", expected_contains="转给谁|收款")
tc(64, "wealth-disambig-cancel-then-transfer", ["理财", "取消", "我要转账"], expected_status="INTERRUPTED", expected_contains="转给谁|收款")
tc(65, "multiple-cancel-then-new", ["我要转账", "取消", "查账单", "算了", "推荐理财"], expected_status="DISAMBIGUATION", expected_contains="咨询|解读")
tc(66, "same-domain-new-thread", ["给张三转100", "给李四转200"], expected_status="COMPLETED", expected_contains="李四|200")
tc(67, "ambiguous-then-wrong-answer", ["理财", "购买"], expected_status="COMPLETED")  # 回答不在候选中，可能被拒绝或选默认
tc(68, "transfer-suspend-bill-suspend-resume-transfer", ["我要转账", "张三", "查账单", "上个月", "推荐理财", "继续转账500"], expected_status="INTERRUPTED", expected_contains="转账|金额|转给谁")
tc(69, "chat-between-ops", ["你好", "我要转账", "张三", "天气不错", "500"], expected_status="COMPLETED", expected_contains="张三|500|成功")
tc(70, "rapid-domain-switch", ["我要转账", "查账单", "推荐理财", "解读金享利"], expected_status="INTERRUPTED", expected_contains="解读|产品")

# ========== 10. Corner Cases - Prompt robustness (10) ==========
print("\n--- 10. Corner Cases - Prompt Robustness ---")
tc(71, "口语化-转个钱", ["转个钱给王五"], expected_status="INTERRUPTED", expected_contains="金额|多少")
tc(72, "口语化-花钱多少", ["这个月花了多少钱"], expected_status="INTERRUPTED", expected_contains="收支|类型|支出")
tc(73, "口语化-帮我看看理财", ["帮我看看有什么理财产品"], expected_status="DISAMBIGUATION", expected_contains="咨询|解读")
tc(74, "口语化-汇点钱", ["汇点钱给赵六"], expected_status="INTERRUPTED", expected_contains="金额|多少")
tc(75, "闲聊-谢谢", ["谢谢"], expected_status="COMPLETED")
tc(76, "闲聊-你是谁", ["你是谁"], expected_status="COMPLETED")
tc(77, "UNSUPPORTED-房贷", ["房贷利率多少"], expected_status="COMPLETED", expected_contains="不支持")
tc(78, "UNSUPPORTED-挂失", ["银行卡挂失"], expected_status="COMPLETED", expected_contains="不支持")
tc(79, "混合意图-先转账后查账", ["帮我转账500给张三然后查账单"], expected_status="INTERRUPTED", expected_contains="金额|转给谁|转账")  # 先处理转账
tc(80, "财富语义区分-基金赎回=UNSUPPORTED", ["基金赎回"], expected_status="COMPLETED", expected_contains="不支持")

print("\n" + "=" * 60)
color = "\033[92m" if fail_count == 0 else "\033[91m"
print(f"{color} RESULTS: {pass_count} PASS / {fail_count} FAIL / {pass_count + fail_count} TOTAL\033[0m")
print("=" * 60)

# Save results
with open("D:/mobile-agent/mobile-ai-demo-enhanced/test_results_r3.txt", "w", encoding="utf-8") as f:
    f.write(f"L0-L1-L2 Comprehensive Test Suite (Round 3)\n")
    f.write(f"Date: {time.strftime('%Y-%m-%d %H:%M:%S')}\n")
    f.write(f"Results: {pass_count} PASS / {fail_count} FAIL / {pass_count + fail_count} TOTAL\n\n")
    for r in results_detail:
        tag = r["tag"]
        num = r["num"]
        desc = r["desc"]
        status = r["status"]
        reason = r.get("reason", "")
        f.write(f"[{tag}] #{num} {desc} -> status={status}")
        if reason:
            f.write(f" | Reason: {reason}")
        f.write("\n")

print("Results saved to test_results_r3.txt")
sys.exit(0 if fail_count == 0 else 1)
