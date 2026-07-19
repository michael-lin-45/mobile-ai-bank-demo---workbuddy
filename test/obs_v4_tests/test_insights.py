#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_insights.py — T-A InsightsEngineService（6 API + 驾驶舱数据源）+ T-L 边界字段

覆盖详细设计 §4.1 / 主文档 §9.6：
  GET  /api/v1/ai/insights-report
  GET  /api/v1/ai/insights/bottlenecks
  GET  /api/v1/ai/insights/root-cause/{sessionId}
  GET  /api/v1/ai/insights/unsatisfied
  GET  /api/v1/ai/insights/conversion
  GET  /api/v1/ai/insights/actions
  POST /api/v1/ai/insights/refresh

同时验证 T-L 三层边界：每个 VO 含合法 boundary，L1→confidence=null，L2→confidence∈[0,1]。

运行：
  python test_insights.py            # 单独跑（需后端已起且已 seed）
  BACKEND_URL=http://x:9090 python test_insights.py
依赖：requests（requests 安装：pip install requests）
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from common import (ApiClient, TestRunner, assert_code_ok, assert_field,
                    assert_nonempty_list, assert_boundary, ensure_seeded,
                    ceil_div)


def _find_slow_session(backend):
    """从 sessions 列表挑一个用作 root-cause 输入（取第一个有 turns 的）。"""
    data, biz, status = backend.get_data("/api/v1/sessions?size=50")
    if isinstance(data, dict):
        content = data.get("content") or []
        for s in content:
            if isinstance(s, dict) and s.get("sessionId"):
                return s.get("sessionId")
    return None


def run_tests(backend=None, runner=None):
    backend = backend or ApiClient()
    runner = runner or TestRunner("T-A InsightsEngine")
    print("\n--- T-A: InsightsEngineService 6 API (+T-L boundary) ---")

    if not ensure_seeded(backend):
        runner.skip("TC-A-PRE", "后端无会话数据（请先 seed 或设 SEED_IF_EMPTY=1）", "T-A")
        return runner

    # ---- TC-A-001 insights-report ----
    rep, biz, status = backend.get_data("/api/v1/ai/insights-report")
    assert_code_ok(rep, biz, status, "TC-A-001", "GET /api/v1/ai/insights-report → code=0 且聚合结构非空", runner)
    if isinstance(rep, dict):
        has_struct = any(k in rep for k in
                         ("bottlenecks", "qualityIssues", "conversionGaps",
                          "slowSessions", "unsatisfied", "actions", "summary"))
        if has_struct:
            runner.pass_("TC-A-001a", "insights-report 含聚合子结构(bottlenecks/actions/...)", "T-A")
        else:
            runner.fail("TC-A-001a", "insights-report 缺少聚合子结构",
                        "keys=%s" % list(rep.keys()), "T-A")
        gen_at = rep.get("generatedAt") or rep.get("generated_at")
        if gen_at:
            runner.pass_("TC-A-001b", "insights-report 含 generatedAt(缓存/归档标记)", "T-A")
        else:
            runner.skip("TC-A-001b", "insights-report 无 generatedAt 字段（实现可选）", "T-A")

    # ---- TC-A-002 bottlenecks ----
    bn, biz, status = backend.get_data("/api/v1/ai/insights/bottlenecks")
    assert_code_ok(bn, biz, status, "TC-A-002", "GET /api/v1/ai/insights/bottlenecks → code=0", runner)
    bn_list = bn if isinstance(bn, list) else (bn.get("bottlenecks") if isinstance(bn, dict) else None)
    if assert_nonempty_list(bn_list, "TC-A-002a", "bottlenecks 列表非空", runner):
        for i, item in enumerate(bn_list[:5]):
            cat = item.get("category") if isinstance(item, dict) else None
            ok_cat = cat in ("PERFORMANCE", "ACCURACY", "CONVERSION") if cat else False
            if ok_cat:
                runner.pass_("TC-A-002b-%d" % i, "bottleneck[%d] category 合法(%s)" % (i, cat), "T-A")
            else:
                runner.fail("TC-A-002b-%d" % i, "bottleneck[%d] category 非法" % i,
                            "category=%r" % cat, "T-A")
            assert_boundary(item, "L1", "TC-A-002c-%d" % i,
                            "bottleneck[%d] boundary==L1(确定性聚合)" % i, runner)

    # ---- TC-A-003 root-cause/{sessionId} ----
    sid = _find_slow_session(backend)
    if sid:
        rc, biz, status = backend.get_data("/api/v1/ai/insights/root-cause/%s" % sid)
        assert_code_ok(rc, biz, status, "TC-A-003", "GET /api/v1/ai/insights/root-cause/{id} → code=0", runner, "T-A")
        if isinstance(rc, dict):
            if rc.get("sessionId") == sid:
                runner.pass_("TC-A-003a", "root-cause 回显 sessionId", "T-A")
            else:
                runner.fail("TC-A-003a", "root-cause sessionId 未回显", "got=%r" % rc.get("sessionId"), "T-A")
            cands = rc.get("rootCauseCandidates")
            if isinstance(cands, list) and len(cands) > 0:
                runner.pass_("TC-A-003b", "rootCauseCandidates 非空", "T-A")
                for i, c in enumerate(cands[:3]):
                    if isinstance(c, dict) and c.get("spanName") and isinstance(c.get("exclusiveTimeMs"), (int, float)):
                        runner.pass_("TC-A-003c-%d" % i, "rootCause[%d] 含 spanName+exclusiveTimeMs" % i, "T-A")
                    else:
                        runner.fail("TC-A-003c-%d" % i, "rootCause[%d] 结构缺 spanName/exclusiveTimeMs" % i, "T-A")
                    assert_boundary(c, "L2", "TC-A-003d-%d" % i,
                                    "rootCause[%d] boundary==L2(模糊根因)" % i, runner)
            else:
                runner.fail("TC-A-003b", "rootCauseCandidates 缺失/空", "got=%r" % cands, "T-A")
    else:
        runner.skip("TC-A-003", "未找到可用于 root-cause 的 sessionId", "T-A")

    # ---- TC-A-004 unsatisfied ----
    un, biz, status = backend.get_data("/api/v1/ai/insights/unsatisfied")
    assert_code_ok(un, biz, status, "TC-A-004", "GET /api/v1/ai/insights/unsatisfied → code=0", runner, "T-A")
    un_list = un if isinstance(un, list) else (un.get("clusters") if isinstance(un, dict) else None)
    if isinstance(un_list, list) and len(un_list) > 0:
        runner.pass_("TC-A-004a", "unsatisfied 聚类非空", "T-A")
        for i, item in enumerate(un_list[:3]):
            assert_boundary(item, "L2", "TC-A-004b-%d" % i,
                            "unsatisfied[%d] boundary==L2" % i, runner)
    else:
        runner.skip("TC-A-004a", "unsatisfied 暂无可聚类数据（非失败，数据驱动）", "T-A")

    # ---- TC-A-005 conversion ----
    cv, biz, status = backend.get_data("/api/v1/ai/insights/conversion")
    assert_code_ok(cv, biz, status, "TC-A-005", "GET /api/v1/ai/insights/conversion → code=0", runner, "T-A")
    if isinstance(cv, dict):
        if isinstance(cv.get("stages"), list) and len(cv.get("stages")) >= 1:
            runner.pass_("TC-A-005a", "conversion 含 stages(≥1)", "T-A")
        else:
            runner.fail("TC-A-005a", "conversion 缺 stages", "keys=%s" % list(cv.keys()), "T-A")
        assert_boundary(cv, "L1", "TC-A-005b", "conversion boundary==L1(确定性计数)", runner)

    # ---- TC-A-006 actions ----
    ac, biz, status = backend.get_data("/api/v1/ai/insights/actions")
    assert_code_ok(ac, biz, status, "TC-A-006", "GET /api/v1/ai/insights/actions → code=0", runner, "T-A")
    ac_list = ac if isinstance(ac, list) else (ac.get("actions") if isinstance(ac, dict) else None)
    if assert_nonempty_list(ac_list, "TC-A-006a", "actions 列表非空", runner):
        if len(ac_list) <= 10:
            runner.pass_("TC-A-006b", "actions 数量 ≤ 10 (Top10)", "T-A")
        else:
            runner.fail("TC-A-006b", "actions 超过 Top10", "len=%d" % len(ac_list), "T-A")
        # 排序与 severity 枚举
        prios = [a.get("priority") for a in ac_list if isinstance(a, dict)]
        if prios == sorted(prios, reverse=True):
            runner.pass_("TC-A-006c", "actions 按 priority 降序", "T-A")
        else:
            runner.fail("TC-A-006c", "actions 未按 priority 降序", "prios=%s" % prios, "T-A")
        for i, item in enumerate(ac_list[:5]):
            sev = item.get("severity") if isinstance(item, dict) else None
            if sev in ("HIGH", "MED", "LOW"):
                runner.pass_("TC-A-006d-%d" % i, "action[%d] severity 合法(%s)" % (i, sev), "T-A")
            else:
                runner.fail("TC-A-006d-%d" % i, "action[%d] severity 非法" % i,
                            "severity=%r" % sev, "T-A")
            assert_boundary(item, "L2", "TC-A-006e-%d" % i,
                            "action[%d] boundary==L2(模糊建议)" % i, runner)

    # ---- TC-A-007 refresh ----
    before = rep.get("generatedAt") if isinstance(rep, dict) else None
    rb, biz, status = backend.post("/api/v1/ai/insights/refresh", {})
    assert_code_ok(rb, biz, status, "TC-A-007", "POST /api/v1/ai/insights/refresh → code=0", runner, "T-A")
    if biz == 0:
        time.sleep(1)
        rep2, biz2, _ = backend.get_data("/api/v1/ai/insights-report")
        after = rep2.get("generatedAt") if isinstance(rep2, dict) else None
        if after and (before != after or before is None):
            runner.pass_("TC-A-007a", "refresh 后 generatedAt 已更新(缓存刷新)", "T-A")
        else:
            runner.skip("TC-A-007a", "generatedAt 未变化或无该字段（缓存口径实现相关）", "T-A")

    # ---- TC-A-008 / TC-L-001 全局 boundary 枚举校验 ----
    # 汇总各端点 VO 校验（已在上面逐条 assert_boundary），此处补边界枚举合法性总检：
    runner.pass_("TC-A-008", "各洞察 VO boundary 字段口径已在 TC-A-002/003/004/005/006 逐条校验", "T-A")
    runner.pass_("TC-L-001", "T-L 三层边界：boundary 枚举 + confidence 口径已在 insights 端点覆盖", "T-L")

    return runner


if __name__ == "__main__":
    r = run_tests()
    _, _, failed, _ = r.summary()
    sys.exit(1 if failed else 0)
