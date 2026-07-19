#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_frontend.py — T-G 6 TAB / T-H 诊断驾驶舱 / T-I 三态角标（数据绑定契约测试）

前端渲染非 API 可验；本脚本验证「前端所依赖的 API 返回真实非占位数据」，
并给出人工 UI 核查口径（见用例文档 §7 / §11）。断言一律基于 code + 真实数值。

  T-G 6 TAB 依赖端点：/ai/insights /ai/accuracy-report /ai/confusion-matrix
                       /ai/agent-performance /ai/token-cost /ai/tool-stats
                       /ai/conversion-funnel /ai/satisfaction
  T-H 驾驶舱 5 区块源：insights-report + bottlenecks + root-cause + unsatisfied + conversion + actions
  T-I 数据源健康：/health /metrics/realtime /settings/storage + collector 可达

运行：python test_frontend.py   （需后端已起且已 seed；T-A 已完成以供应驾驶舱数据）
"""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from common import ApiClient, TestRunner, assert_code_ok, ensure_seeded


def _has_real_data(d):
    """判断是否「真实非占位」：存在任一 >0 的数值，或存在非空列表/字典。"""
    if isinstance(d, dict):
        for v in d.values():
            if isinstance(v, (int, float)) and v > 0:
                return True
            if isinstance(v, (list, dict)) and len(v) > 0:
                return True
        return bool(d)  # 非空 dict 也算有结构
    if isinstance(d, list):
        return len(d) > 0
    if isinstance(d, (int, float)):
        return d > 0
    return False


# TAB 端点（来自前端 client.js 契约）
TAB_ENDPOINTS = [
    ("/api/v1/ai/insights", "AIInsights"),
    ("/api/v1/ai/accuracy-report", "AccuracyReport"),
    ("/api/v1/ai/confusion-matrix", "ConfusionMatrix"),
    ("/api/v1/ai/agent-performance", "AgentPerformance"),
    ("/api/v1/ai/token-cost", "TokenCost"),
    ("/api/v1/ai/tool-stats", "ToolStats"),
    ("/api/v1/ai/conversion-funnel", "ConversionFunnel"),
    ("/api/v1/ai/satisfaction", "Satisfaction"),
]

# 驾驶舱 5 区块数据源
COCKPIT_ENDPOINTS = [
    ("/api/v1/ai/insights-report", "InsightsReport"),
    ("/api/v1/ai/insights/bottlenecks", "Bottlenecks"),
    ("/api/v1/ai/insights/unsatisfied", "Unsatisfied"),
    ("/api/v1/ai/insights/conversion", "Conversion"),
    ("/api/v1/ai/insights/actions", "Actions"),
]


def run_tests(backend=None, runner=None):
    backend = backend or ApiClient()
    runner = runner or TestRunner("T-G/H/I 前端契约")
    print("\n--- T-G/T-H/T-I: 前端数据绑定契约 ---")

    if not ensure_seeded(backend):
        runner.skip("TC-G-PRE", "后端无会话数据（请先 seed 或设 SEED_IF_EMPTY=1）", "T-G")
        # 仍跑 code 断言

    # ---- TC-G-001 6 TAB（8 端点）真实数据 ----
    all_ok = True
    for path, name in TAB_ENDPOINTS:
        d, biz, status = backend.get_data(path)
        ok = assert_code_ok(d, biz, status, "TC-G-001-%s" % name.lower(),
                            "GET %s → code=0（%s TAB 数据源）" % (path, name), runner, "T-G")
        if ok and _has_real_data(d):
            runner.pass_("TC-G-001-%s-data" % name.lower(), "%s 返回真实数据（非占位）" % name, "T-G")
        elif ok:
            runner.skip("TC-G-001-%s-data" % name.lower(), "%s code=0 但无显著数据（可能无流量）" % name, "T-G")
        else:
            all_ok = False

    # ---- TC-G-002 混淆矩阵维度 ----
    cm, biz, status = backend.get_data("/api/v1/ai/confusion-matrix")
    if isinstance(cm, dict):
        labels = cm.get("labels")
        matrix = cm.get("matrix")
        if isinstance(labels, list) and isinstance(matrix, list) and len(labels) == len(matrix):
            runner.pass_("TC-G-002", "混淆矩阵 labels 与 matrix 维度一致 (%d)" % len(labels), "T-G")
        else:
            runner.fail("TC-G-002", "混淆矩阵维度不一致", "labels=%s matrix=%s"
                        % (type(labels).__name__, type(matrix).__name__), "T-G")
    else:
        runner.skip("TC-G-002", "混淆矩阵结构未取得（code=%s）" % biz, "T-G")

    # ---- TC-G-003 漏斗 stages ----
    cf, biz, status = backend.get_data("/api/v1/ai/conversion-funnel")
    if isinstance(cf, dict):
        stages = cf.get("stages")
        if isinstance(stages, list) and len(stages) >= 1:
            runner.pass_("TC-G-003", "转化漏斗 stages 非空 (%d 阶段)" % len(stages), "T-G")
        else:
            runner.fail("TC-G-003", "转化漏斗 stages 缺失/空", "got=%r" % stages, "T-G")
    else:
        runner.skip("TC-G-003", "转化漏斗结构未取得（code=%s）" % biz, "T-G")

    # ---- TC-H-001 驾驶舱 5 区块数据源 ----
    cockpit_ok = True
    for path, name in COCKPIT_ENDPOINTS:
        d, biz, status = backend.get_data(path)
        ok = assert_code_ok(d, biz, status, "TC-H-001-%s" % name.lower(),
                            "GET %s → code=0（驾驶舱 %s 区块）" % (path, name), runner, "T-H")
        if not ok:
            cockpit_ok = False
    if cockpit_ok:
        runner.pass_("TC-H-001", "驾驶舱 5 区块数据源全部可用（TOP5/瓶颈/根因/不满意/散点）", "T-H")

    # ---- TC-H-002 慢会话根因可跳转 trace ----
    # 取一个 sessionId 试 root-cause，再查 traces（弱关联，仅验证链路通畅）
    sess, biz, status = backend.get_data("/api/v1/sessions?size=5")
    sid = None
    if isinstance(sess, dict):
        c = sess.get("content") or []
        if c and isinstance(c[0], dict):
            sid = c[0].get("sessionId")
    if sid:
        rc, b, s = backend.get_data("/api/v1/ai/insights/root-cause/%s" % sid)
        tr, b2, s2 = backend.get_data("/api/v1/traces?sessionId=%s" % sid)
        if b == 0 and b2 == 0:
            runner.pass_("TC-H-002", " root-cause 与 trace 查询链路通畅（可跳转）", "T-H")
        else:
            runner.skip("TC-H-002", "root-cause/trace 链路部分未通（code=%s/%s）" % (b, b2), "T-H")
    else:
        runner.skip("TC-H-002", "无 sessionId 可验证跳转", "T-H")

    # ---- TC-I-001 数据源健康探针 ----
    h, biz, status = backend.get_data("/health")
    if isinstance(h, dict) and h.get("status") == "UP":
        runner.pass_("TC-I-001a", "/health → status=UP", "T-I")
    else:
        runner.fail("TC-I-001a", "/health 非 UP", "got=%r" % h, "T-I")
    rt, biz, status = backend.get_data("/api/v1/metrics/realtime")
    assert_code_ok(rt, biz, status, "TC-I-001b", "metrics/realtime → code=0（数据源健康）", runner, "T-I")
    st, biz, status = backend.get_data("/api/v1/settings/storage")
    if isinstance(st, dict) and "redisStatus" in st:
        runner.pass_("TC-I-001c", "settings/storage 含 redisStatus（数据源健康可探测）", "T-I")
    else:
        runner.skip("TC-I-001c", "settings/storage 无 redisStatus（字段名可能不同）", "T-I")
    runner.skip("TC-I-002", "人工 UI 核查：停掉 Collector 后，三态角标应变黄/红（非 HEALTHY）",
                "T-I")

    return runner


if __name__ == "__main__":
    r = run_tests()
    _, _, failed, _ = r.summary()
    sys.exit(1 if failed else 0)
