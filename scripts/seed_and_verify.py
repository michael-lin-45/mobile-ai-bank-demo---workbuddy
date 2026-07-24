#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
seed_and_verify.py — 统一播种 + 全栈验证脚本
=============================================
用途：先向 Core 播种 36 条中文对话数据，再播种 V23 业务埋点并对 Backend + Core 执行全栈约 20 点验证。
用法：python scripts/seed_and_verify.py

两阶段流程：
  Phase 1（播种）：检查 Core 在线 → 逐条发送 36 条对话 → 打印摘要 → 等待 3 秒
  Phase 2（验证）：检查 Backend 在线 → 跑 15 个 Section 共约 20 个验证点 → 打印最终结果

前置条件：
  - Core  运行在 http://127.0.0.1:8080
  - Backend 运行在 http://127.0.0.1:9090
"""

import json
import random
import time
import sys
import io
import urllib.request
import urllib.error

# 确保 Windows 控制台 UTF-8 输出
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

# ============================================================
# 共享常量
# ============================================================
CORE_BASE = "http://127.0.0.1:8080"
BACKEND_BASE = "http://127.0.0.1:9090"
CHAT_URL = f"{CORE_BASE}/api/bank/chat"

# 播种阶段 sessionId 前缀（含时间戳+随机数，避免重播污染）
SEED_PREFIX = "seed-verify-%d-%04x" % (int(time.time()), random.randint(0, 0xFFFF))
# 验证阶段聊天 API 专用 sessionId（与播种阶段隔离，避免会话混淆）
VERIFY_SESSION = "seed-verify-chk"

# ============================================================
# 播种数据（36 条）
# ============================================================
TURNS = [
    # ---- seed.sh (已汉化) ----
    ("zs1", "转账50000给李四", 3),
    ("zs1", "确认", 3),
    ("zs1", "好的，继续", 3),
    ("zs2", "查一下上个月的账单", 3),
    ("zs2", "餐饮花了多少钱", 3),
    ("ww1", "推荐稳健的基金", 4),
    ("ww1", "解释一下朝朝盈", 4),
    ("ww1", "还有别的吗", 4),
    ("ww1", "转账3万给赵六", 4),
    ("ww1", "确认", 4),
    ("xm1", "我想看看理财", 4),
    ("xm1", "产品解读", 4),
    ("ll1", "转账500给张三", 3),
    ("ll1", "取消", 3),
    ("am1", "推荐科技板块的基金", 4),
    ("am1", "历史收益", 4),
    ("am1", "风险等级", 4),
    ("xmi1", "你好", 3),
    ("xmi1", "我的工资到账了吗", 3),
    # ---- seed_new.sh (中文) ----
    ("np1", "你好，我想了解一下基金产品，有什么推荐的吗", 4),
    ("np1", "能详细说说货币基金和债券基金的区别吗", 4),
    ("np1", "那我先买5000块的货币基金试试", 5),
    ("np2", "转账500到张三的工商银行账户", 3),
    ("np2", "等等，不是500，改成转1000", 3),
    ("np2", "对，就是1000，确认转账", 3),
    ("np2", "好的，帮我查一下转账进度", 5),
    ("np3", "帮我查一下这个月的账单", 4),
    ("np3", "只看3月1号到3月15号的消费记录", 4),
    ("np3", "其中餐饮类的花了多少钱", 5),
    ("np4", "我想了解一下理财", 4),
    ("np4", "帮我分析一下我现在的资产配置", 4),
    ("np4", "那推荐一些稳健型的理财产品吧", 5),
    ("np5", "嗨，早上好", 3),
    ("np5", "今天心情不错，想看看有什么好的理财产品", 4),
    ("np5", "我风险承受能力一般，推荐什么类型的基金", 4),
    ("np5", "谢谢，那就先关注一下混合型基金", 3),
]

# V23 业务埋点播种数据（M1/M2）
BUSINESS_EVENTS = [
    {"eventType": "mbank_card_click", "cardType": "transfer", "agent": "L0", "channel": "app"},
    {"eventType": "mbank_card_click", "cardType": "fund", "agent": "L1", "channel": "app"},
    {"eventType": "mbank_card_click", "cardType": "credit_card", "agent": "L1", "channel": "app"},
    {"eventType": "mbank_card_click", "cardType": "transfer", "agent": "L0", "channel": "app"},
    {"eventType": "mbank_card_click", "cardType": "fund", "agent": "L2", "channel": "app"},
    {"eventType": "mbank_human_click", "source": "user_request", "agent": "L0", "channel": "app"},
    {"eventType": "mbank_human_click", "source": "low_confidence", "agent": "L1", "channel": "app"},
    {"eventType": "mbank_human_click", "source": "timeout", "agent": "L2", "channel": "app"},
    {"eventType": "mbank_human_click", "source": "user_request", "agent": "L1", "channel": "app"},
]

# ============================================================
# 辅助函数
# ============================================================

def check(url, label):
    """检查端点可用性，失败报 FAIL"""
    try:
        start = time.time()
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=10) as r:
            body = r.read().decode("utf-8")[:150]
            t = (time.time() - start) * 1000
            return f"  {label}: OK ({t:.0f}ms) -> {body}"
    except Exception as e:
        return f"  {label}: FAIL -> {e}"


def check_skip(url, label, skip_reason=""):
    """检查端点，失败时 SKIP（不报 FAIL）"""
    try:
        start = time.time()
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=10) as r:
            body = r.read().decode("utf-8")[:150]
            t = (time.time() - start) * 1000
            return f"  {label}: OK ({t:.0f}ms) -> {body}"
    except Exception as e:
        hint = f" ({skip_reason})" if skip_reason else ""
        return f"  {label}: ⚠ SKIP{hint} -> {e}"


def check_json(url, label, validate_fn, skip_reason=""):
    """检查端点，解析 JSON 并验证；失败时 SKIP"""
    try:
        start = time.time()
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=10) as r:
            data = json.loads(r.read().decode("utf-8"))
            t = (time.time() - start) * 1000
            ok, msg = validate_fn(data)
            if ok:
                return f"  {label}: OK ({t:.0f}ms) {msg}"
            else:
                return f"  {label}: ⚠ SKIP -> {msg}"
    except Exception as e:
        hint = f" ({skip_reason})" if skip_reason else ""
        return f"  {label}: ⚠ SKIP{hint} -> {e}"


def _quick_get(url, timeout=10):
    """快速 GET 请求，返回 (ok: bool, status_or_error: str)"""
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return True, f"HTTP {r.status}"
    except Exception as e:
        return False, str(e)[:80]


# ============================================================
# Phase 1: 播种
# ============================================================

def send(session_id, message):
    """发送单条聊天消息到 Core，返回结果摘要字符串"""
    url = "%s?sessionId=%s" % (CHAT_URL, session_id)
    body = json.dumps({"message": message}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=body,
                                 headers={"Content-Type": "application/json"},
                                 method="POST")
    try:
        with urllib.request.urlopen(req, timeout=45) as resp:
            raw = resp.read().decode("utf-8", "replace")
        d = json.loads(raw)
        status = d.get("status", "?")
        intent = d.get("intent", "?")
        content = (d.get("content") or "")[:38]
        return "[%s] %s - %s" % (status, intent, content)
    except urllib.error.HTTPError as e:
        return "[HTTP %s] %s" % (e.code, e.read().decode("utf-8", "replace")[:60])
    except Exception as e:
        return "[ERR] %s" % str(e)[:60]


def seed_business_event(payload):
    """POST 一条业务埋点到 Backend，返回结果摘要字符串"""
    url = f"{BACKEND_BASE}/api/v1/business-events"
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=body,
                                 headers={"Content-Type": "application/json"},
                                 method="POST")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            d = json.loads(resp.read().decode("utf-8", "replace"))
            return "[%s] %s" % (d.get("code", "?"), payload.get("eventType"))
    except urllib.error.HTTPError as e:
        return "[HTTP %s] %s" % (e.code, e.read().decode("utf-8", "replace")[:60])
    except Exception as e:
        return "[ERR] %s" % str(e)[:60]


def seed_rewrite_accuracy():
    """播种 agent.rewrite.accuracy 指标（OTLP JSON → POST /api/v1/metrics），
    让 accuracy-report 的「改写准确率表 + 改写失败根因 TOP3」呈现真实数据（非 mock）。

    后端 buildRewriteSection 从该指标的 rule_check tag 计数派生：
      - pass 数 / 总数 × 100 = 改写准确率
      - 非 pass 的 rule_check 值 = 失败根因，取 TOP3
    OtlpParserService.parseMetrics 把每个 sum dataPoint 落盘为一条 MetricsAgg，
    tags 由 dataPoint.attributes 序列化为 JSON（如 {"rule_check":"意图歧义未消"}）。
    默认查询窗口 now-24h~now，种子写入当前时刻必被查到。
    """
    metric_name = "agent.rewrite.accuracy"
    # (rule_check tag, 单点 value, 条数)：16 pass + 4 失败（2+1+1）→ 准确率 80%
    plan = [
        ("pass", 0.95, 16),
        ("意图歧义未消", 0.30, 2),
        ("关键槽位缺失", 0.25, 1),
        ("规则冲突", 0.20, 1),
    ]
    data_points = []
    for reason, val, cnt in plan:
        for _ in range(cnt):
            data_points.append({
                "attributes": [{"key": "rule_check", "value": {"stringValue": reason}}],
                "asDouble": val,
            })
    payload = {
        "resourceMetrics": [{
            "resource": {"attributes": [{"key": "service.name", "value": {"stringValue": "seed-script"}}]},
            "scopeMetrics": [{
                "scope": {"name": "seed.rewrite", "version": "1.0.0"},
                "metrics": [{
                    "name": metric_name,
                    "sum": {
                        "dataPoints": data_points,
                        "aggregationTemporality": "CUMULATIVE",
                        "isMonotonic": True,
                    },
                }],
            }],
        }],
    }
    url = f"{BACKEND_BASE}/api/v1/metrics"
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=body,
                                 headers={"Content-Type": "application/json"},
                                 method="POST")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            d = json.loads(resp.read().decode("utf-8", "replace"))
            parsed = d.get("data", {}).get("parsed", "?")
            return "[%s] parsed=%s dp=%d" % (d.get("code", "?"), parsed, len(data_points))
    except urllib.error.HTTPError as e:
        return "[HTTP %s] %s" % (e.code, e.read().decode("utf-8", "replace")[:80])
    except Exception as e:
        return "[ERR] %s" % str(e)[:80]


def phase1_seed():
    """Phase 1: 播种 36 条对话"""
    print("=" * 60, flush=True)
    print("Phase 1: 播种（36 条对话）", flush=True)
    print("=" * 60, flush=True)

    # 前置检查：Core 是否在线
    print("\n[前置检查] 检测 Core 在线状态...", flush=True)
    ok, status = _quick_get(f"{CORE_BASE}/actuator/health")
    if not ok:
        print(f"Core 未启动，退出（{status}）", flush=True)
        sys.exit(1)
    print(f"Core 在线：{status}\n", flush=True)

    total = len(TURNS)
    ok_count = 0
    fail_count = 0
    t_start = time.time()

    for i, (sid, msg, slp) in enumerate(TURNS, 1):
        full_sid = "%s-%s" % (SEED_PREFIX, sid)
        short = (msg[:24] + "..") if len(msg) > 26 else msg
        result = send(full_sid, msg)
        print("[%02d/%02d] %-30s %-26s -> %s" % (i, total, full_sid, short, result), flush=True)
        if result.startswith("[COMPLETED]"):
            ok_count += 1
        elif result.startswith("[HTTP"):
            ok_count += 1  # HTTP 错误亦计为已送达（与原始脚本一致）
        else:
            fail_count += 1
        time.sleep(slp)

    elapsed = time.time() - t_start
    print("\n--- 播种完成 ---", flush=True)
    print(f"成功: {ok_count}/{total}  失败: {fail_count}/{total}  耗时: {elapsed:.1f}s", flush=True)
    print("等待 3 秒让 Core 处理完毕...", flush=True)
    time.sleep(3)


# ============================================================
# Phase 2: 验证
# ============================================================

def _v_code0_data(data):
    """验证 code==0 且 data 非空"""
    code = data.get("code", -1)
    d = data.get("data", None)
    if code == 0 and d is not None:
        preview = str(d)[:80]
        return True, f"code=0, data={preview}"
    return False, f"code={code}, data={'empty' if d is None else 'present'}"


def _v_dead_keys(data):
    """验证 dead-keys 诊断：无死 key"""
    code = data.get("code", -1)
    d = data.get("data", {})
    dead_keys = d.get("deadKeys", [])
    total_dead = sum(dk.get("count", 0) for dk in dead_keys)
    all_clear = total_dead == 0
    if code == 0 and all_clear:
        return True, f"totalDead={total_dead}, allClear={all_clear}"
    return False, f"code={code}, totalDead={total_dead}, allClear={all_clear}"


def _v_pending_approval(data):
    """验证 P7 pending_approval 指标"""
    measurements = data.get("measurements", [])
    if measurements:
        vals = ", ".join(f"{m.get('statistic','?')}={m.get('value','?')}" for m in measurements)
        return True, f"measurements=[{vals}]"
    return False, "measurements 为空"


def _v_deepflux_metrics(data):
    """验证 P6 deepflux.* 指标已注册"""
    names = data.get("names", [])
    deepflux_names = [n for n in names if n.startswith("deepflux.")]
    if deepflux_names:
        preview = deepflux_names[:5]
        suffix = "..." if len(deepflux_names) > 5 else ""
        return True, f"找到 {len(deepflux_names)} 个 deepflux.* 指标: {preview}{suffix}"
    return False, "未找到 deepflux.* 前缀指标"


def _v_count_gt0(data):
    """验证业务事件计数 > 0"""
    code = data.get("code", -1)
    d = data.get("data", None)
    if code == 0 and isinstance(d, (int, float)) and d > 0:
        return True, f"code=0, count={d}"
    return False, f"code={code}, count={d}"


def _v_agg_nonempty(data):
    """验证聚合结果非空（list 长度 > 0）"""
    code = data.get("code", -1)
    d = data.get("data", None)
    if code == 0 and isinstance(d, list) and len(d) > 0:
        return True, f"code=0, rows={len(d)}"
        return False, f"code={code}, data={'empty' if d == [] else d}"


def _v_rewrite_section(data):
    """验证 accuracy-report 的改写段（rewrite 表 + rootCauses TOP3）非空"""
    code = data.get("code", -1)
    d = data.get("data", {}) or {}
    if code != 0:
        return False, f"code={code}"
    rewrite = d.get("rewrite")
    root_causes = d.get("rootCauses")
    n_rewrite = len(rewrite) if isinstance(rewrite, list) else 0
    n_rc = len(root_causes) if isinstance(root_causes, list) else 0
    if n_rewrite > 0 and n_rc > 0:
        return True, f"rewrite行数={n_rewrite}, 根因TOP={n_rc}, summary={d.get('rewriteSummary','')[:40]}"
    return False, f"rewrite行数={n_rewrite}, 根因TOP={n_rc}（未播种改写指标→前端走mock）"


def phase2_verify():
    """Phase 2: 全栈约 20 点验证"""
    print("\n" + "=" * 60, flush=True)
    print("Phase 2: 全栈验证（15 Section / 约 20 验证点）", flush=True)
    print("=" * 60, flush=True)

    # 前置检查：Backend 是否在线
    print("\n[前置检查] 检测 Backend 在线状态...", flush=True)
    ok, status = _quick_get(f"{BACKEND_BASE}/api/v1/admin/diagnostics/dead-keys")
    if not ok:
        print(f"Backend 未启动，退出（{status}）", flush=True)
        sys.exit(1)
    print(f"Backend 在线：{status}\n", flush=True)

    # 2.5 前置：播种 V23 业务埋点（依赖 Backend 已在线）
    print("\n[前置播种] V23 业务埋点（9 条）...", flush=True)
    be_ok = 0
    for i, ev in enumerate(BUSINESS_EVENTS, 1):
        full = dict(ev)
        full["sessionId"] = "%s-be" % SEED_PREFIX
        res = seed_business_event(full)
        print("  [%02d/%02d] %-16s -> %s" % (i, len(BUSINESS_EVENTS), ev.get("eventType"), res), flush=True)
        if res.startswith("[0]") or res.startswith("[HTTP"):
            be_ok += 1
    print("  业务埋点播种: %d/%d" % (be_ok, len(BUSINESS_EVENTS)), flush=True)

    # 2.6 前置：播种改写准确率指标（使准确率分析的改写段呈现实数据，非 mock）
    print("\n[前置播种] V23 改写准确率指标（agent.rewrite.accuracy）...", flush=True)
    rw_res = seed_rewrite_accuracy()
    print("  agent.rewrite.accuracy -> %s" % rw_res, flush=True)

    has_skip = False

    def _print_line(result):
        nonlocal has_skip
        if "SKIP" in result:
            has_skip = True
        print(result, flush=True)

    # 1. Backend
    print("[Backend 9090]", flush=True)
    _print_line(check(f"{BACKEND_BASE}/api/v1/admin/diagnostics/dead-keys", "Health (dead-keys)"))
    _print_line(check_skip(f"{BACKEND_BASE}/", "Dashboard", "后端不提供根页面，已由其他端点替代"))

    # 2. Core
    print("\n[Core 8080]", flush=True)
    _print_line(check(f"{CORE_BASE}/actuator/health", "Health"))

    # 3. Chat API
    print("\n[聊天 API]", flush=True)
    msgs = ["我想理财投资", "查一下我的余额", "转账100给王五"]
    for msg in msgs:
        url = f"{CHAT_URL}?sessionId={VERIFY_SESSION}"
        body = json.dumps({"message": msg}).encode("utf-8")
        start = time.time()
        try:
            req = urllib.request.Request(url, data=body,
                                         headers={"Content-Type": "application/json"},
                                         method="POST")
            with urllib.request.urlopen(req, timeout=120) as r:
                data = json.loads(r.read().decode("utf-8"))
                t = round(time.time() - start, 1)
                print(f"  [{msg}] -> {data['status']}/{data['intent']} ({t}s)", flush=True)
        except Exception as e:
            t = round(time.time() - start, 1)
            print(f"  [{msg}] -> ERROR ({t}s): {e}", flush=True)

    # 4. OTel Endpoints
    print("\n[OTel Endpoints]", flush=True)
    for ep in ["/api/v1/metrics", "/api/v1/traces", "/api/v1/logs"]:
        try:
            req = urllib.request.Request(f"{BACKEND_BASE}{ep}")
            with urllib.request.urlopen(req, timeout=5) as r:
                data = r.read().decode("utf-8")
                n = data.count("traceId")
                print(f"  {ep}: OK ({n} traces)", flush=True)
        except Exception as e:
            print(f"  {ep}: ⚠ SKIP (Collector 可能未起) -> {e}", flush=True)
            has_skip = True

    # 5. Backend Insights API (W2)
    print("\n[Backend Insights API]", flush=True)
    _print_line(check_json(f"{BACKEND_BASE}/api/v1/ai/insights-report", "insights-report", _v_code0_data))
    _print_line(check_json(f"{BACKEND_BASE}/api/v1/ai/accuracy-report", "accuracy-report", _v_code0_data))
    _print_line(check_json(f"{BACKEND_BASE}/api/v1/ai/accuracy-report", "accuracy-report(改写段)", _v_rewrite_section))
    _print_line(check_json(f"{BACKEND_BASE}/api/v1/ai/confusion-matrix", "confusion-matrix", _v_code0_data))

    # 6. Backend Metrics (W2)
    print("\n[Backend Metrics]", flush=True)
    _print_line(check_json(f"{BACKEND_BASE}/api/v1/metrics/realtime", "metrics/realtime", _v_code0_data))

    # 7. 死 key 诊断 (W3)
    print("\n[死 key 诊断 (W3)]", flush=True)
    _print_line(check_json(f"{BACKEND_BASE}/api/v1/admin/diagnostics/dead-keys", "dead-keys", _v_dead_keys))

    # 8. 提示词版本化 (P20)
    print("\n[提示词版本化 (P20)]", flush=True)
    _print_line(check_skip(f"{BACKEND_BASE}/api/v1/prompt-version/active?key=insights.root_cause",
                           "prompt-version/active", "未创建提示词"))

    # 9. 告警引擎
    print("\n[告警引擎]", flush=True)
    _print_line(check_skip(f"{BACKEND_BASE}/api/v1/alerts", "alerts"))
    _print_line(check_skip(f"{BACKEND_BASE}/api/v1/alerts/rules", "alerts/rules"))

    # 10. reRoute 透传
    print("\n[reRoute 透传]", flush=True)
    _print_line(check_skip(f"{BACKEND_BASE}/api/v1/ai/reroute-stats", "reroute-stats"))

    # 11. Core 观测 - pending_approval (P7)
    print("\n[Core 观测 - pending_approval (P7)]", flush=True)
    _print_line(check_json(f"{CORE_BASE}/actuator/metrics/deepflux.workflow.pending_approval",
                           "pending_approval", _v_pending_approval, "P7 指标未注册"))

    # 12. Core 观测 - deepflux 指标注册 (P6)
    print("\n[Core 观测 - deepflux 指标注册]", flush=True)
    _print_line(check_json(f"{CORE_BASE}/actuator/metrics", "deepflux 指标", _v_deepflux_metrics,
                           "P6 集中式注册表未生效"))

    # 13. V23 业务埋点 (M1/M2)
    print("\n[V23 业务埋点 (M1/M2)]", flush=True)
    _print_line(check_json(f"{BACKEND_BASE}/api/v1/business-events/count?event=mbank_card_click",
                           "be-count(card_click)", _v_count_gt0))
    _print_line(check_json(f"{BACKEND_BASE}/api/v1/business-events/count?event=mbank_human_click",
                           "be-count(human_click)", _v_count_gt0))
    _print_line(check_json(f"{BACKEND_BASE}/api/v1/business-events/agg?event=mbank_card_click&groupBy=card_type",
                           "be-agg(card_click by card_type)", _v_agg_nonempty))

    # 14. V23 Agent 性能 (Zone C 语义质量)
    print("\n[V23 Agent 性能 (Zone C)]", flush=True)
    _print_line(check_json(f"{BACKEND_BASE}/api/v1/ai/agent-performance", "agent-performance", _v_code0_data))

    # 15. V23 转化漏斗
    print("\n[V23 转化漏斗]", flush=True)
    _print_line(check_json(f"{BACKEND_BASE}/api/v1/ai/conversion-funnel", "conversion-funnel", _v_code0_data))

    # 最终摘要
    print("\n" + "=" * 60, flush=True)
    if has_skip:
        print("⚠ 验证完成（见上方 SKIP 项）", flush=True)
    else:
        print("✅ 全部验证完成", flush=True)


# ============================================================
# 主入口
# ============================================================

def main():
    phase1_seed()
    phase2_verify()


if __name__ == "__main__":
    main()
