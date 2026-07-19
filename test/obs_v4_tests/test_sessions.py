#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_sessions.py — T-D T29 listSessions DB 分页

覆盖详细设计 §4.3：
  GET /api/v1/sessions?page=&size= 返回 DB 侧分页（totalElements/totalPages/content）
  - 分页数学正确（totalPages == ceil(totalElements/size)）
  - 大 offset 不 OOM / 不 500（以响应耗时 < 2s 作代理断言）
  - 排序稳定（同 page 两次顺序一致）
  - 组合筛选生效
  - 不存在筛选返回空列表 code=0

运行：python test_sessions.py   （需后端已起且已 seed）
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from common import (ApiClient, TestRunner, assert_code_ok, ceil_div, ensure_seeded)


def _page(backend, params):
    return backend.get_data("/api/v1/sessions?" + "&".join(
        "%s=%s" % (k, v) for k, v in params.items()))


def run_tests(backend=None, runner=None):
    backend = backend or ApiClient()
    runner = runner or TestRunner("T-D listSessions 分页")
    print("\n--- T-D: T29 listSessions DB 分页 ---")

    if not ensure_seeded(backend):
        runner.skip("TC-D-PRE", "后端无会话数据（请先 seed 或设 SEED_IF_EMPTY=1）", "T-D")
        return runner

    # ---- TC-D-001 基础分页 ----
    data, biz, status = _page(backend, {"page": 0, "size": 20})
    assert_code_ok(data, biz, status, "TC-D-001", "GET /api/v1/sessions?page=0&size=20 → code=0", runner, "T-D")
    if isinstance(data, dict):
        te = data.get("totalElements")
        tp = data.get("totalPages")
        content = data.get("content")
        num = data.get("number", data.get("page", 0))
        if isinstance(te, int) and te >= 0:
            runner.pass_("TC-D-001a", "totalElements 为非负整数 (%d)" % te, "T-D")
        else:
            runner.fail("TC-D-001a", "totalElements 缺失/非整数", "got=%r" % te, "T-D")
        if isinstance(tp, int) and tp == ceil_div(te if isinstance(te, int) else 0, 20):
            runner.pass_("TC-D-001b", "totalPages == ceil(totalElements/20) (%d)" % tp, "T-D")
        else:
            runner.fail("TC-D-001b", "totalPages 计算错误", "tp=%r te=%r" % (tp, te), "T-D")
        if isinstance(content, list) and len(content) <= 20:
            runner.pass_("TC-D-001c", "content 为列表且长度 ≤ size (%d)" % len(content), "T-D")
        else:
            runner.fail("TC-D-001c", "content 非列表或超 size", "got=%r" % content, "T-D")
        if num == 0:
            runner.pass_("TC-D-001d", "number==0（首页）", "T-D")
        else:
            runner.fail("TC-D-001d", "number 不为 0", "got=%r" % num, "T-D")
    else:
        runner.fail("TC-D-001x", "响应 data 非 dict", "got=%s" % type(data).__name__, "T-D")

    # ---- TC-D-002 分页数学（size=5）----
    d0, _, _ = _page(backend, {"page": 0, "size": 5})
    d1, _, _ = _page(backend, {"page": 1, "size": 5})
    if isinstance(d0, dict) and isinstance(d1, dict):
        c0 = d0.get("content") or []
        c1 = d1.get("content") or []
        ids0 = {s.get("sessionId") for s in c0 if isinstance(s, dict)}
        ids1 = {s.get("sessionId") for s in c1 if isinstance(s, dict)}
        if ids0 and ids1 and ids0.isdisjoint(ids1):
            runner.pass_("TC-D-002", "page0 与 page1 内容不重叠", "T-D")
        else:
            runner.fail("TC-D-002", "分页内容重叠或为空", "p0=%d p1=%d" % (len(ids0), len(ids1)), "T-D")
        te = d0.get("totalElements")
        tp = d0.get("totalPages")
        if isinstance(tp, int) and tp == ceil_div(te if isinstance(te, int) else 0, 5):
            runner.pass_("TC-D-002a", "totalPages(size=5) 正确 (%d)" % tp, "T-D")
        else:
            runner.fail("TC-D-002a", "totalPages(size=5) 错误", "tp=%r te=%r" % (tp, te), "T-D")
    else:
        runner.fail("TC-D-002", "分页数学校验失败（响应非 dict）", "T-D")

    # ---- TC-D-003 大 offset 不 OOM / 不 500 ----
    t0 = time.time()
    dbig, bizb, statusb = _page(backend, {"page": 500, "size": 200})
    dt = time.time() - t0
    if statusb != 500 and bizb != 500:
        runner.pass_("TC-D-003", "大 offset(page=500,size=200) 返回非 500 (http=%s)" % statusb, "T-D")
    else:
        runner.fail("TC-D-003", "大 offset 返回 500（疑似全量内存/溢出）", "T-D")
    if dt < 2.0:
        runner.pass_("TC-D-003a", "大 offset 响应耗时 < 2s (%.2fs，代理「无全量内存」)" % dt, "T-D")
    else:
        runner.fail("TC-D-003a", "大 offset 响应耗时偏长 (%.2fs)，疑似未走 DB 分页" % dt, "T-D")

    # ---- TC-D-004 排序稳定 ----
    a, _, _ = _page(backend, {"page": 0, "size": 10})
    b, _, _ = _page(backend, {"page": 0, "size": 10})
    if isinstance(a, dict) and isinstance(b, dict):
        ida = [s.get("sessionId") for s in (a.get("content") or []) if isinstance(s, dict)]
        idb = [s.get("sessionId") for s in (b.get("content") or []) if isinstance(s, dict)]
        if ida == idb:
            runner.pass_("TC-D-004", "同 page 两次顺序一致（排序稳定）", "T-D")
        else:
            runner.fail("TC-D-004", "同 page 两次顺序不一致（排序不稳定）", "T-D")

    # ---- TC-D-005 组合筛选 ----
    df, bizf, statusf = _page(backend, {"page": 0, "size": 20, "status": "completed"})
    assert_code_ok(df, bizf, statusf, "TC-D-005", "组合筛选 status=completed → code=0", runner, "T-D")
    if isinstance(df, dict):
        cf = df.get("content") or []
        if cf:
            ok = all(s.get("status") == "completed" for s in cf if isinstance(s, dict))
            if ok:
                runner.pass_("TC-D-005a", "筛选结果均满足 status=completed", "T-D")
            else:
                runner.fail("TC-D-005a", "筛选结果含非 completed", "T-D")
        else:
            runner.skip("TC-D-005a", "completed 筛选无数据（非失败）", "T-D")

    # ---- TC-D-006 不存在筛选 ----
    de, bize, statuse = _page(backend, {"page": 0, "size": 20, "userId": "nonexistent_xyz_123"})
    assert_code_ok(de, bize, statuse, "TC-D-006", "不存在 userId → code=0", runner, "T-D")
    if isinstance(de, dict):
        ce = de.get("content") or []
        if len(ce) == 0 and de.get("totalElements") == 0:
            runner.pass_("TC-D-006a", "不存在筛选返回空列表且 totalElements=0", "T-D")
        else:
            runner.fail("TC-D-006a", "不存在筛选应返回空", "totalElements=%s" % de.get("totalElements"), "T-D")

    return runner


if __name__ == "__main__":
    r = run_tests()
    _, _, failed, _ = r.summary()
    sys.exit(1 if failed else 0)
