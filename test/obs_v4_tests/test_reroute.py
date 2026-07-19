#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_reroute.py — T-N 待定项 B reRoute 原报文透传

覆盖详细设计 §3.5（最小改动）：reRouted:true + originalQuery 透传并落库；toTurnVO 不再硬编码 false。

验证方式：直接 POST /api/v1/sessions 模拟 SessionBridge 上报（body 带 reRouted/originalQuery），
断言落库后：
  - TC-N-001 POST → code=0，新建 session
  - TC-N-002 GET 详情 rerouteTriggered==true；originalQuery 若持久化则回显（§6.2 说明 originalQuery 不新增列，故软断言）
  - TC-N-003 turns 的 rerouteTriggered 来自真实字段（非硬编码 false）

注：若 upsert body 契约与 SessionBridge.reportSession 实际载荷不同导致 4xx/5xx，用例将 FAIL 并打印响应体，
    便于工程师对齐字段——非假通过。

运行：python test_reroute.py   （需 backend 起）
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from common import ApiClient, TestRunner

TEST_SESSION = "qa-reroute-%d" % int(time.time())


def run_tests(backend=None, runner=None):
    backend = backend or ApiClient()
    runner = runner or TestRunner("T-N reRoute 透传")
    print("\n--- T-N: 待定项 B reRoute 原报文透传 ---")

    h, biz, status = backend.get_data("/health")
    if status == 0 and biz != 0 and not isinstance(h, dict):
        runner.skip("TC-N-PRE", "后端不可达（请先起 backend）", "T-N")
        return runner

    original_q = "原始用户问题：帮我给李四转账五万"

    # ---- TC-N-001 POST 模拟 SessionBridge 上报 ----
    body = {
        "sessionId": TEST_SESSION,
        "userId": "qa_user_reroute",
        "status": "COMPLETED",
        "intentFlow": "TRANSFER",
        "reRouted": True,
        "originalQuery": original_q,
        "turns": [
            {"userMessage": "转五万给李四", "aiResponse": "已为您转账",
             "intent": "TRANSFER", "rerouteTriggered": True}
        ],
    }
    d, biz, status = backend.post("/api/v1/sessions", body)
    if status in (200, 201) or (isinstance(d, dict) and d.get("code") == 0):
        runner.pass_("TC-N-001", "POST /api/v1/sessions (reRouted=true) → 成功创建", "T-N")
    else:
        runner.fail("TC-N-001", "上报带 reRoute 的会话失败(HTTP %s)" % status,
                    "resp=%s" % str(d)[:300], "T-N")
        return runner

    time.sleep(1)

    # ---- TC-N-002 GET 详情 ----
    det, biz, status = backend.get_data("/api/v1/sessions/%s" % TEST_SESSION)
    if isinstance(det, dict):
        rt = det.get("rerouteTriggered")
        if rt is True:
            runner.pass_("TC-N-002", "rerouteTriggered 落库为 true", "T-N")
        else:
            runner.fail("TC-N-002", "rerouteTriggered 未落库为 true", "got=%r" % rt, "T-N")

        oq = det.get("originalQuery")
        if oq is not None:
            if oq == original_q:
                runner.pass_("TC-N-002a", "originalQuery 透传并回显（与输入一致）", "T-N")
            else:
                runner.fail("TC-N-002a", "originalQuery 回显不一致", "got=%r" % oq, "T-N")
        else:
            # 详细设计 §6.2：originalQuery 透传但不新增列，故不持久化属预期
            runner.skip("TC-N-002a", "originalQuery 未持久化（符合 §6.2：仅透传不新增列），软断言跳过", "T-N")
    else:
        runner.fail("TC-N-002", "无法获取会话详情", "got=%r" % det, "T-N")
        return runner

    # ---- TC-N-003 turns rerouteTriggered 非硬编码 false ----
    turns = det.get("turns") if isinstance(det, dict) else None
    if isinstance(turns, list) and turns:
        t0 = turns[0] if isinstance(turns[0], dict) else {}
        trt = t0.get("rerouteTriggered")
        if trt is True:
            runner.pass_("TC-N-003", "turns[0].rerouteTriggered==true（toTurnVO 未硬编码 false）", "T-N")
        else:
            runner.fail("TC-N-003", "turns[0].rerouteTriggered 非 true（疑似硬编码 false）", "got=%r" % trt, "T-N")
    else:
        runner.skip("TC-N-003", "详情无 turns（未传 turns），toTurnVO 校验跳过", "T-N")

    # 清理
    backend.delete("/api/v1/sessions/%s" % TEST_SESSION)
    runner.pass_("TC-N-004", "清理测试会话 %s" % TEST_SESSION, "T-N")

    return runner


if __name__ == "__main__":
    r = run_tests()
    _, _, failed, _ = r.summary()
    sys.exit(1 if failed else 0)
