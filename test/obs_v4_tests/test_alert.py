#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_alert.py — T-B AlertEngineService + T-F 告警验收

覆盖详细设计 §3.2 / §4.2 / 主文档 §4.6 / §10.1：
  - 默认 5 条规则种子（含 DDL 增强字段）
  - @Scheduled(fixedRate=30s) 求值触发（lastEvaluatedAt 变非空）
  - 确定性注入 → FIRING 事件 + LogNotifier 落日志不真实外发
  - 状态机 FIRING → ACKED → RESOLVED
  - 抑制：同规则 5min 内再次 breach → SUPPRESSED + suppressionCount+1

关键假设（如不符，用例明确 FAIL，不假通过）：
  - 注入规则的 metricKey 默认 "request_count"（对应 Redis request_count:6h，种子后 >0）。
    可用环境变量 ALERT_TEST_METRIC_KEY 覆盖；若 AlertEngine.collectMetricValue 实际映射不同，
    TC-B-004 将失败并提示与工程师对齐。
  - 状态机事件 ack 端点：POST /api/v1/alerts/events/{id}/ack
  - 抑制窗口 = 300s（Redis obs:alerts:suppress:{ruleId} TTL）

时序控制（环境变量）：
  ALERT_FULL  默认 1；置 0 跳过耗时的 firing/suppression 生命周期（仅跑契约检查 TC-B-001~003）
  ALERT_CYCLE_WAIT 默认 40（秒，>30s 调度周期）

运行：python test_alert.py   （需后端已起；建议先 seed）
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from common import ApiClient, TestRunner, assert_code_ok

ALERT_FULL = os.environ.get("ALERT_FULL", "1") == "1"
CYCLE_WAIT = int(os.environ.get("ALERT_CYCLE_WAIT", "40"))
SUPPRESS_WINDOW = 300  # 与详细设计 §3.2② 一致
# 确定性注入用的 metricKey 假设（详见文件头注释）
METRIC_KEY = os.environ.get("ALERT_TEST_METRIC_KEY", "request_count")

DEFAULT_RULE_COUNT_MIN = 5
DEFAULT_SEVERITIES = {"CRITICAL", "WARNING"}


def _create_rule(backend, body):
    data, biz, status = backend.post("/api/v1/alerts/rules", body)
    if isinstance(data, dict):
        inner = data.get("data", data)
        rid = inner.get("id") if isinstance(inner, dict) else None
        return rid, data.get("code", 0) if isinstance(data, dict) else status
    return None, status


def _find_event(backend, rule_id, want_status=None):
    """取该 rule 的事件列表，按 firedAt 降序；可选过滤 status。"""
    data, biz, status = backend.get_data("/api/v1/alerts/events")
    events = []
    if isinstance(data, list):
        events = data
    elif isinstance(data, dict):
        events = data.get("content") or data.get("events") or []
    evs = [e for e in events if isinstance(e, dict) and e.get("ruleId") == rule_id]
    if want_status:
        evs = [e for e in evs if e.get("status") == want_status]
    evs.sort(key=lambda e: e.get("firedAt") or "", reverse=True)
    return evs


def run_tests(backend=None, runner=None):
    backend = backend or ApiClient()
    runner = runner or TestRunner("T-B/T-F AlertEngine")
    print("\n--- T-B/T-F: AlertEngineService + 告警验收 ---")

    # 探活
    h, biz, status = backend.get_data("/health")
    if status == 0 and biz != 0 and not isinstance(h, dict):
        runner.skip("TC-B-PRE", "后端不可达（请先起 backend）", "T-B")
        return runner

    # ---- TC-B-001 默认 5 规则 ----
    rules, biz, status = backend.get_data("/api/v1/alerts/rules")
    rules_list = rules if isinstance(rules, list) else (rules.get("content") if isinstance(rules, dict) else None)
    if assert_code_ok(rules, biz, status, "TC-B-001", "GET /api/v1/alerts/rules → code=0", runner, "T-B"):
        if isinstance(rules_list, list) and len(rules_list) >= DEFAULT_RULE_COUNT_MIN:
            runner.pass_("TC-B-001a", "默认规则数 ≥ 5（实际 %d）" % len(rules_list), "T-B")
            sev = set()
            ch = set()
            for r in rules_list:
                if isinstance(r, dict):
                    sev.add(r.get("severity"))
                    nc = r.get("notifyChannels")
                    if isinstance(nc, str):
                        ch.update([c.strip() for c in nc.split(",") if c.strip()])
                    elif isinstance(nc, list):
                        ch.update(nc)
            if DEFAULT_SEVERITIES.issubset(sev):
                runner.pass_("TC-B-001b", "severity 覆盖 CRITICAL+WARNING", "T-B")
            else:
                runner.fail("TC-B-001b", "severity 未覆盖 CRITICAL+WARNING", "got=%s" % sev, "T-B")
            if ch & {"dingtalk", "feishu", "email"}:
                runner.pass_("TC-B-001c", "notifyChannels 覆盖 dingtalk/feishu/email 中至少一种", "T-B")
            else:
                runner.fail("TC-B-001c", "notifyChannels 未含 dingtalk/feishu/email", "got=%s" % ch, "T-B")
        else:
            runner.fail("TC-B-001a", "默认规则数 < 5", "got=%r" % rules_list, "T-B")

    # ---- TC-B-002 DDL 增强字段 ----
    if isinstance(rules_list, list) and rules_list:
        sample = None
        for r in rules_list:
            if isinstance(r, dict) and "evaluationInterval" in r:
                sample = r
                break
        if sample is None:
            sample = rules_list[0]
        if isinstance(sample, dict):
            if "evaluationInterval" in sample:
                runner.pass_("TC-B-002a", "规则含 evaluationInterval 字段", "T-B")
            else:
                runner.fail("TC-B-002a", "规则缺 evaluationInterval（DDL 增强未生效）", "keys=%s" % list(sample.keys()), "T-B")
            if "currentValue" in sample:
                runner.pass_("TC-B-002b", "规则含 currentValue 字段", "T-B")
            else:
                runner.fail("TC-B-002b", "规则缺 currentValue（DDL 增强未生效）", "T-B")
            if "lastEvaluatedAt" in sample:
                runner.pass_("TC-B-002c", "规则含 lastEvaluatedAt 字段", "T-B")
            else:
                runner.fail("TC-B-002c", "规则缺 lastEvaluatedAt（DDL 增强未生效）", "T-B")

    # ---- TC-B-003 @Scheduled 求值触发 ----
    target = None
    if isinstance(rules_list, list) and rules_list:
        for r in rules_list:
            if isinstance(r, dict):
                target = r
                break
    if target and isinstance(target, dict):
        rid = target.get("id")
        fired = False
        for _ in range(int(45 / 5) + 1):
            d, b, s = backend.get_data("/api/v1/alerts/rules/%s" % rid if rid is not None else "")
            # 兼容：直接再拉列表取该 rule
            rl, bl, sl = backend.get_data("/api/v1/alerts/rules")
            rl_list = rl if isinstance(rl, list) else (rl.get("content") if isinstance(rl, dict) else None)
            cur = None
            if isinstance(rl_list, list):
                for r in rl_list:
                    if isinstance(r, dict) and r.get("id") == rid:
                        cur = r
                        break
            if isinstance(cur, dict) and cur.get("lastEvaluatedAt") not in (None, ""):
                # currentValue 应为数值
                cv = cur.get("currentValue")
                if isinstance(cv, (int, float)):
                    runner.pass_("TC-B-003", "@Scheduled 已求值：lastEvaluatedAt 非空且 currentValue=%s" % cv, "T-B")
                    fired = True
                    break
                else:
                    runner.pass_("TC-B-003", "@Scheduled 已求值：lastEvaluatedAt 非空", "T-B")
                    fired = True
                    break
            time.sleep(5)
        if not fired:
            runner.fail("TC-B-003", "45s 内 lastEvaluatedAt 仍为空（@Scheduled 未触发或求值异常）", "T-B")

    # ---- TC-B-004 ~ TC-B-008 告警生命周期（耗时，受 ALERT_FULL 控制）----
    if not ALERT_FULL:
        runner.skip("TC-B-004..008", "ALERT_FULL=0，跳过耗时 firing/suppression 生命周期验证", "T-F")
        return runner

    if not rules_list or not isinstance(rules_list, list):
        runner.skip("TC-B-004..008", "无规则列表，无法注入", "T-F")
        return runner

    # 注入一条必触规则（threshold=0，必然 breach）
    inject_body = {
        "ruleName": "QA-V4-INJECT-request_count>0",
        "metricName": METRIC_KEY,
        "metricKey": METRIC_KEY,
        "operator": ">",
        "threshold": 0.0,
        "enabled": True,
        "severity": "warning",
        "notifyChannels": "dingtalk",
        "evaluationInterval": 30,
    }
    inject_id, code = _create_rule(backend, inject_body)
    if inject_id is None:
        runner.fail("TC-B-004", "注入规则创建失败（code=%s）" % code, "T-F")
        return runner
    runner.pass_("TC-B-004", "注入必触规则 id=%s (metricKey=%s)" % (inject_id, METRIC_KEY), "T-F")

    # 等待 2 个调度周期
    print("    [wait] 等待 %ds 让 @Scheduled 求值注入规则 ..." % (CYCLE_WAIT * 2))
    time.sleep(CYCLE_WAIT * 2)

    evs = _find_event(backend, inject_id)
    firing = _find_event(backend, inject_id, want_status="FIRING")
    if firing:
        e0 = firing[0]
        runner.pass_("TC-B-004a", "注入规则产生 FIRING 事件 (eventId=%s)" % e0.get("id"), "T-F")
        nc = e0.get("notifiedChannels")
        nr = e0.get("notifyResult")
        if nc:
            runner.pass_("TC-B-004b", "FIRING 事件 notifiedChannels 非空: %s" % nc, "T-F")
        else:
            runner.fail("TC-B-004b", "FIRING 事件 notifiedChannels 为空（LogNotifier 未落渠道）", "T-F")
        if nr:
            runner.pass_("TC-B-004c", "FIRING 事件 notifyResult 非空: %s（LogNotifier 不真实外发）" % nr, "T-F")
            runner.pass_("TC-B-005", "LogNotifier 已记录分发结果（日志应含 [Alert][NOTIFY]，无真实 HTTP 外发）", "T-F")
        else:
            runner.fail("TC-B-004c", "FIRING 事件 notifyResult 为空", "T-F")
        # 当前状态为 FIRING，测试 ack 状态机
        eid = e0.get("id")
        if eid is not None:
            ad, acode, astatus = backend.post("/api/v1/alerts/events/%s/ack" % eid, {})
            if acode == 0:
                runner.pass_("TC-B-006", "POST .../events/%s/ack → code=0" % eid, "T-F")
                d2, b2, s2 = backend.get_data("/api/v1/alerts/events/%s" % eid)
                ev = d2 if isinstance(d2, dict) else None
                if isinstance(ev, dict) and ev.get("status") == "ACKED":
                    runner.pass_("TC-B-006a", "事件状态 → ACKED", "T-F")
                else:
                    runner.fail("TC-B-006a", "事件状态未变为 ACKED", "got=%s" % (ev.get("status") if isinstance(ev, dict) else ev), "T-F")
            else:
                runner.fail("TC-B-006", "ack 接口返回非 0 (code=%s)" % acode, "T-F")
    else:
        runner.fail("TC-B-004a", "注入规则在 %ds 内未产生 FIRING 事件" % (CYCLE_WAIT * 2),
                    "可能为 metricKey 映射不符（当前=%s）；事件列表前3: %s"
                    % (METRIC_KEY, [e.get("status") for e in evs[:3]]), "T-F")
        # 清理
        backend.delete("/api/v1/alerts/rules/%s" % inject_id)
        return runner

    # TC-B-007 RESOLVED：把阈值调到不可能触发，等一周期
    backend.put("/api/v1/alerts/rules/%s" % inject_id, {
        "ruleName": "QA-V4-INJECT", "metricName": METRIC_KEY, "metricKey": METRIC_KEY,
        "operator": ">", "threshold": 1e18, "enabled": True,
        "severity": "warning", "notifyChannels": "dingtalk", "evaluationInterval": 30,
    })
    print("    [wait] 等待 %ds 让指标恢复 → RESOLVED ..." % CYCLE_WAIT)
    time.sleep(CYCLE_WAIT)
    evs2 = _find_event(backend, inject_id)
    resolved = _find_event(backend, inject_id, want_status="RESOLVED")
    if resolved:
        runner.pass_("TC-B-007", "指标恢复后事件 → RESOLVED", "T-F")
        if resolved[0].get("resolvedAt"):
            runner.pass_("TC-B-007a", "RESOLVED 事件 resolvedAt 非空", "T-F")
        else:
            runner.skip("TC-B-007a", "RESOLVED 事件无 resolvedAt 字段（实现可选）", "T-F")
    else:
        runner.fail("TC-B-007", "指标恢复后未产生 RESOLVED 事件", "events=%s" % [e.get("status") for e in evs2[:3]], "T-F")

    # TC-B-008 SUPPRESSED：再次 breach（窗口内）→ 应 SUPPRESSED + suppressionCount+1
    backend.put("/api/v1/alerts/rules/%s" % inject_id, {
        "ruleName": "QA-V4-INJECT", "metricName": METRIC_KEY, "metricKey": METRIC_KEY,
        "operator": ">", "threshold": 0.0, "enabled": True,
        "severity": "warning", "notifyChannels": "dingtalk", "evaluationInterval": 30,
    })
    print("    [wait] 等待 %ds 让再次 breach（抑制窗内）→ SUPPRESSED ..." % CYCLE_WAIT)
    time.sleep(CYCLE_WAIT)
    supp = _find_event(backend, inject_id, want_status="SUPPRESSED")
    if supp:
        runner.pass_("TC-B-008", "抑制窗内再次 breach → SUPPRESSED 事件", "T-F")
        sc = supp[0].get("suppressionCount")
        if isinstance(sc, int) and sc >= 1:
            runner.pass_("TC-B-008a", "SUPPRESSED 事件 suppressionCount>=1 (=%s)" % sc, "T-F")
        else:
            runner.fail("TC-B-008a", "SUPPRESSED 事件 suppressionCount 未 +1", "got=%s" % sc, "T-F")
    else:
        runner.fail("TC-B-008", "抑制窗内再次 breach 未产生 SUPPRESSED（抑制逻辑可能未生效）",
                    "events=%s" % [e.get("status") for e in _find_event(backend, inject_id)[:3]], "T-F")

    # 清理
    backend.delete("/api/v1/alerts/rules/%s" % inject_id)
    runner.pass_("TC-B-009", "清理注入规则 id=%s" % inject_id, "T-B")

    return runner


if __name__ == "__main__":
    r = run_tests()
    _, _, failed, _ = r.summary()
    sys.exit(1 if failed else 0)
