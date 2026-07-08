#!/usr/bin/env python3
"""
AI 可观测系统 — 播种 + 聊天核心测试 + 后端API验证 (V2)
用法: python seed_and_test.py
前提: Core:8080 + Backend:9090 + Frontend:3000 已启动
"""
import json, time, sys, urllib.request, urllib.error

CORE = "http://127.0.0.1:8080"
BACKEND = "http://127.0.0.1:9090"
TIMEOUT = 90
PASS = 0
FAIL = 0
FAILURES = []

def chat(sid, msg):
    url = f"{CORE}/api/bank/chat?sessionId={sid}"
    body = json.dumps({"message": msg, "content": msg}).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json; charset=utf-8"})
    try:
        start = time.time()
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            raw = resp.read().decode("utf-8")
            data = json.loads(raw) if raw else {"status": "EMPTY", "content": "(empty)"}
        elapsed = int((time.time() - start) * 1000)
        status = data.get("status", "?")
        content = (data.get("content") or data.get("answer") or "")[:60]
        question = (data.get("question") or "")[:60]
        print(f"  -> {elapsed}ms | status={status} | {content}", flush=True)
        return data
    except Exception as e:
        msg = str(e)[:100]
        print(f"  -> ERROR: {msg}", flush=True)
        return {"status": "ERROR", "content": msg, "question": ""}

def backend_get(path):
    url = f"{BACKEND}{path}"
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            body = json.loads(e.read().decode("utf-8"))
            return body
        except:
            return {"http_code": e.code, "error": str(e)}
    except Exception as e:
        return {"error": str(e)}

def ok(test_id, name, data, check_fn):
    global PASS, FAIL
    try:
        result = check_fn(data)
    except Exception as e:
        result = False
        data = {"status": "EXCEPTION", "content": str(e)}
    if result:
        PASS += 1
        print(f"  [PASS] {test_id} {name}", flush=True)
    else:
        FAIL += 1
        detail = f"status={data.get('status','?')} content={str(data.get('content',''))[:50]}"
        FAILURES.append(f"[FAIL] {test_id} {name} | {detail}")
        print(f"  [FAIL] {test_id} {name} | {detail}", flush=True)

# ==========================================
# Phase 1: 播种数据 (7 sessions, 19 turns)
# ==========================================
print("=" * 50, flush=True)
print("  Phase 1: 播种测试数据", flush=True)
print("=" * 50, flush=True)

seeds = [
    ("seed-zs1", "帮我转50000给李四，用途是房租", 2),
    ("seed-zs1", "确定，就是他", 2),
    ("seed-zs1", "好的，确认转账", 2),
    ("seed-zs2", "帮我查一下上个月的账单明细", 2),
    ("seed-zs2", "那餐饮支出多少", 2),
    ("seed-ww1", "我比较保守，有没有稳健的理财产品推荐", 3),
    ("seed-ww1", "帮我解读一下朝朝盈这个产品", 3),
    ("seed-ww1", "除了朝朝盈还有什么", 3),
    ("seed-ww1", "算了，帮我转3万给赵六", 3),
    ("seed-ww1", "确认", 3),
    ("seed-xm1", "我想看看理财", 3),
    ("seed-xm1", "产品解读", 3),
    ("seed-ll1", "转500给张三", 2),
    ("seed-ll1", "算了不转了，取消", 2),
    ("seed-am1", "我是激进型投资者，推荐几只科技领域的基金", 3),
    ("seed-am1", "这几只基金历史收益怎么样", 3),
    ("seed-am1", "风险等级呢", 3),
    ("seed-xmi1", "你好，今天天气不错", 2),
    ("seed-xmi1", "对了，我的工资到账了吗", 2),
]

for sid, msg, delay in seeds:
    print(f"\n>>> {sid}: {msg[:30]}...", flush=True)
    chat(sid, msg)
    time.sleep(delay)

print("\n播种完成！7 sessions, 19 turns", flush=True)

# ==========================================
# Phase 2: 聊天核心测试
# ==========================================
print("\n" + "=" * 50, flush=True)
print("  Phase 2: 聊天核心测试", flush=True)
print("=" * 50, flush=True)

# 1. Transfer full flow
s = 't-tsf1'; r = chat(s, "我想转账")
ok("01", "Transfer-Start", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION"))

r = chat(s, "张三")
ok("02", "Transfer-Payee", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION") and "金额" in str(d.get("question", "")))

r = chat(s, "100")
ok("03", "Transfer-Done", r, lambda d: d.get("status") == "COMPLETED" and "转账成功" in str(d.get("content", "作")))

# 2. Bill query
s = 't-bill1'; r = chat(s, "查一下账单")
ok("04", "Bill-Start", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION") and "时间" in str(d.get("question", "")))
r = chat(s, "最近一个月")
ok("05", "Bill-Range", r, lambda d: d.get("status") in ("COMPLETED", "INTERRUPTED", "NEEDS_CONFIRMATION"))

# 3. WealthConsult
s = 't-wc1'; r = chat(s, "我想咨询理财")
ok("06", "WealthConsult-Start", r, lambda d: d.get("status") in ("INTERRUPTED", "DISAMBIGUATION", "NEEDS_CONFIRMATION"))
if r.get("status") == "DISAMBIGUATION":
    r = chat(s, "理财咨询")
    ok("06b", "WC-Disambig", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION"))
r = chat(s, "稳健")
ok("07", "WealthConsult-Done", r, lambda d: d.get("status") in ("COMPLETED", "INTERRUPTED"))

# 4. WealthInterpret
s = 't-wi1'; r = chat(s, "帮我解读理财产品")
ok("08", "WealthInterp-Start", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION") and "产品" in str(d.get("question", "")))
r = chat(s, "朝朝盈")
ok("09", "WealthInterp-Done", r, lambda d: d.get("status") == "COMPLETED")

# 5. Disambiguation
s = 't-dis1'; r = chat(s, "理财")
ok("10", "Disambig-Wealth", r, lambda d: d.get("status") == "DISAMBIGUATION")
r = chat(s, "理财咨询")
ok("11", "Disambig-Consult", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION"))

s = 't-dis2'; r = chat(s, "理财")
ok("12", "Disambig-Wealth2", r, lambda d: d.get("status") == "DISAMBIGUATION")
r = chat(s, "产品解读")
ok("13", "Disambig-Interpret", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION"))

s = 't-dis3'; r = chat(s, "理财")
ok("14", "Disambig-First", r, lambda d: d.get("status") == "DISAMBIGUATION")
r = chat(s, "理财")
ok("15", "Disambig-StillAmbig", r, lambda d: d.get("status") == "COMPLETED" and "不支持" in str(d.get("content", "")))

# 6. Unsupported
s = 't-uns1'; r = chat(s, "我想买保险")
ok("16", "Unsupported", r, lambda d: d.get("status") == "COMPLETED")

# 7. Cancel
s = 't-can1'; r = chat(s, "转账")
ok("17", "CancelTfr-Start", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION"))
r = chat(s, "取消转账")
ok("18", "CancelTfr", r, lambda d: d.get("status") == "COMPLETED" and "取消" in str(d.get("content", "")))

s = 't-can2'; r = chat(s, "查账单")
ok("19", "CancelBill-Start", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION"))
r = chat(s, "不要了")
ok("20", "CancelBill", r, lambda d: d.get("status") == "COMPLETED" and "取消" in str(d.get("content", "")))

# Mid-flow cancel
s = 't-can3'; r = chat(s, "转账")
ok("21", "CancelMid-Start", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION"))
r = chat(s, "李四")
ok("22", "CancelMid-Payee", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION"))
r = chat(s, "取消")
ok("23", "CancelMid-Done", r, lambda d: d.get("status") == "COMPLETED" and "取消" in str(d.get("content", "")))

# Disambig cancel
s = 't-can4'; r = chat(s, "理财")
ok("24", "CDisambig-Start", r, lambda d: d.get("status") == "DISAMBIGUATION")
r = chat(s, "算了不问了")
ok("25", "CDisambig-Done", r, lambda d: d.get("status") == "COMPLETED" and "取消" in str(d.get("content", "")))

# 8. Switch intent
s = 't-swi1'; r = chat(s, "转账")
ok("26", "Switch-Tfr", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION"))
r = chat(s, "查账单")
ok("27", "Switch-Bill", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION") and "时间" in str(d.get("question", "")))

s = 't-swi2'; r = chat(s, "查账单")
ok("28", "Switch2-Bill", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION"))
r = chat(s, "我想转账")
ok("29", "Switch2-Tfr", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION"))

# 9. Resume suspended
s = 't-res1'; r = chat(s, "转账")
ok("30", "Resume-Start", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION"))
r = chat(s, "张三")
ok("31", "Resume-Payee", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION"))
r = chat(s, "查账单")
ok("32", "Resume-Switch", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION"))
r = chat(s, "继续转账")
ok("33", "Resume-Back", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION"))

# 10. Edge cases
s = 't-edg1'; r = chat(s, "取消")
ok("34", "CancelNothing", r, lambda d: d.get("status") == "COMPLETED")

s = 't-edg2'; r = chat(s, "你好")
ok("35", "Greeting", r, lambda d: d.get("status") == "COMPLETED")

# 11. Mixed queries
s = 't-mix1'; r = chat(s, "查一下我这个星期的开销")
ok("36", "Bill-Quick", r, lambda d: d.get("status") in ("INTERRUPTED", "COMPLETED", "NEEDS_CONFIRMATION"))

s = 't-mix2'; r = chat(s, "转账给李四")
ok("37", "TfrWithPayee-Start", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION"))
r = chat(s, "500")
ok("38", "TfrWithPayee-Done", r, lambda d: d.get("status") == "COMPLETED" and "转账成功" in str(d.get("content", "作")))

# 12. Full transfer 2
s = 't-tfr2'; r = chat(s, "转账")
ok("39", "Tfr2-Start", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION"))
r = chat(s, "王五")
ok("40", "Tfr2-Payee", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION"))
r = chat(s, "200")
ok("41", "Tfr2-Done", r, lambda d: d.get("status") == "COMPLETED" and "转账成功" in str(d.get("content", "作")))

# 13. Bill cancel
s = 't-bc1'; r = chat(s, "查账单")
ok("42", "BC2-Start", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION"))
r = chat(s, "取消查询")
ok("43", "BC2-Done", r, lambda d: d.get("status") == "COMPLETED" and "取消" in str(d.get("content", "")))

# 14. WealthInterpret cancel
s = 't-wic1'; r = chat(s, "解读理财")
ok("44", "WIC-Start", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION"))
r = chat(s, "不想要了")
ok("45", "WIC-Done", r, lambda d: d.get("status") == "COMPLETED" and "取消" in str(d.get("content", "")))

# 15. Full disambig -> consult
s = 't-fdi1'; r = chat(s, "理财")
ok("46", "FDI-Start", r, lambda d: d.get("status") == "DISAMBIGUATION")
r = chat(s, "理财咨询")
ok("47", "FDI-Consult", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION"))
r = chat(s, "激进")
ok("48", "FDI-Done", r, lambda d: d.get("status") in ("COMPLETED", "INTERRUPTED", "NEEDS_CONFIRMATION"))

# 16. Quick extra tests
s = 't-ext1'; r = chat(s, "取消")
chat(s, "取消")
r = chat(s, "取消")
ok("49", "MultiCancel", r, lambda d: d.get("status") == "COMPLETED")

s = 't-ext2'; r = chat(s, "转账")
ok("50", "Extra-TfrStart", r, lambda d: d.get("status") in ("INTERRUPTED", "NEEDS_CONFIRMATION"))
r = chat(s, "理财")
ok("51", "Extra-Disambig", r, lambda d: d.get("status") == "DISAMBIGUATION")

# Summary
print("\n" + "=" * 60, flush=True)
total = PASS + FAIL
print(f"Phase 2 Result: Total={total}  PASS={PASS}  FAIL={FAIL}", flush=True)
print("=" * 60, flush=True)
if FAIL > 0:
    print("\nFailed:", flush=True)
    for f in FAILURES:
        print(f"  {f}", flush=True)

# ==========================================
# Phase 3: 后端 API 验证
# ==========================================
print("\n" + "=" * 50, flush=True)
print("  Phase 3: 后端 API 验证", flush=True)
print("=" * 50, flush=True)

API_PASS = 0
API_FAIL = 0

def check_api(name, data, check_fn):
    global API_PASS, API_FAIL
    try:
        if check_fn(data):
            API_PASS += 1
            print(f"  [PASS] {name}", flush=True)
        else:
            API_FAIL += 1
            print(f"  [FAIL] {name} | {str(data)[:100]}", flush=True)
    except Exception as e:
        API_FAIL += 1
        print(f"  [FAIL] {name} | Exception: {e}", flush=True)

check_api("Health",     backend_get("/health"),
    lambda d: d.get("data", {}).get("status") == "UP")
check_api("Sessions",   backend_get("/api/v1/sessions?page=0&size=5"),
    lambda d: d.get("code") == 0 and "content" in d.get("data", {}))
check_api("AIInsights", backend_get("/api/v1/ai/insights?dimension=model"),
    lambda d: d.get("code") == 0)
check_api("AgentPerf",  backend_get("/api/v1/ai/agent-performance?dimension=agent"),
    lambda d: d.get("code") == 0)
check_api("TokenCost",  backend_get("/api/v1/ai/token-cost?groupBy=model"),
    lambda d: d.get("code") == 0)
check_api("Funnel",     backend_get("/api/v1/ai/conversion-funnel"),
    lambda d: d.get("code") == 0)
check_api("Satisfaction", backend_get("/api/v1/ai/satisfaction"),
    lambda d: d.get("code") == 0)
check_api("SkillStats", backend_get("/api/v1/ai/skill-stats"),
    lambda d: d.get("code") == 0 or d.get("code") == 501)  # 501 = P1 not implemented
check_api("ToolStats",  backend_get("/api/v1/ai/tool-stats"),
    lambda d: d.get("code") == 0)
check_api("AlertRules", backend_get("/api/v1/alerts/rules"),
    lambda d: d.get("code") == 0)
check_api("Settings",   backend_get("/api/v1/settings/storage"),
    lambda d: d.get("code") == 0)
check_api("Logs",       backend_get("/api/v1/logs?level=INFO&page=0&size=3"),
    lambda d: d.get("code") == 0)
check_api("Traces",     backend_get("/api/v1/traces?page=0&size=5"),
    lambda d: d.get("code") == 0)

print(f"\nPhase 3 Result: Total={API_PASS+API_FAIL}  PASS={API_PASS}  FAIL={API_FAIL}", flush=True)
print("=" * 60, flush=True)

# Final
print("\n" + "=" * 60, flush=True)
print("  FINAL SUMMARY", flush=True)
print(f"  Phase 2 (Chat Core):  {PASS}/{PASS+FAIL} passed", flush=True)
print(f"  Phase 3 (Backend API): {API_PASS}/{API_PASS+API_FAIL} passed", flush=True)
print(f"  Grand Total: {PASS+API_PASS}/{PASS+FAIL+API_PASS+API_FAIL} passed", flush=True)
print("=" * 60, flush=True)

sys.exit(0 if FAIL == 0 and API_FAIL == 0 else 1)
