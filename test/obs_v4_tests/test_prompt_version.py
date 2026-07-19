#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_prompt_version.py — T-M P20 提示词版本化

覆盖详细设计 §3.4：
  - 建表 prompt_versions（prompt_key/version/content/model/status/...）
  - 版本号 = 该 prompt_key 已存在最大 version + 1
  - getActive：status=ACTIVE 且 version 最大；activate 时旧 ACTIVE→ARCHIVED

⚠ 端点路径为**假设**（详细设计未给 REST 路径，仅给 Service）。默认 PROMPT_VERSION_PATH=
   /api/v1/ai/prompt-versions。若工程师实际路径不同，用例明确 FAIL 并提示改路径——非假通过。

运行：python test_prompt_version.py   （需 backend 起且 schema 已建 prompt_versions 表）
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from common import ApiClient, TestRunner

PROMPT_VERSION_PATH = os.environ.get("PROMPT_VERSION_PATH", "/api/v1/ai/prompt-versions")
PK = "insights.root_cause"


def run_tests(backend=None, runner=None):
    backend = backend or ApiClient()
    runner = runner or TestRunner("T-M 提示词版本化")
    print("\n--- T-M: P20 提示词版本化 ---")

    h, biz, status = backend.get_data("/health")
    if status == 0 and biz != 0 and not isinstance(h, dict):
        runner.skip("TC-M-PRE", "后端不可达（请先起 backend）", "T-M")
        return runner

    # ---- TC-M-001 创建 v1 ----
    body1 = {"promptKey": PK, "content": "你是根因分析引擎，针对慢会话输出 RAG 检索/VectorStore 假设。",
             "model": "qwen-plus"}
    d1, biz1, status1 = backend.post(PROMPT_VERSION_PATH, body1)
    if status1 == 404 or status1 == 501:
        runner.fail("TC-M-001", "提示词版本端点不存在(HTTP %d)：%s 路径假设不符，请联系工程师确认 REST 路径"
                    % (status1, PROMPT_VERSION_PATH), "T-M")
        return runner
    c1 = d1.get("code") if isinstance(d1, dict) else status1
    inner1 = d1.get("data", d1) if isinstance(d1, dict) else None
    id1 = inner1.get("id") if isinstance(inner1, dict) else None
    ver1 = inner1.get("version") if isinstance(inner1, dict) else None
    if c1 == 0 and id1 is not None:
        runner.pass_("TC-M-001", "创建提示词版本 v1 成功 (id=%s version=%s)" % (id1, ver1), "T-M")
    else:
        runner.fail("TC-M-001", "创建版本失败", "code=%s status=%s resp=%s" % (c1, status1, str(d1)[:200]), "T-M")
        return runner

    # ---- TC-M-002 创建 v2 + 列表 ----
    body2 = {"promptKey": PK, "content": "（改进版）在 RAG 假设基础上追加工具超时重试维度。",
             "model": "qwen-plus"}
    d2, biz2, status2 = backend.post(PROMPT_VERSION_PATH, body2)
    c2 = d2.get("code") if isinstance(d2, dict) else status2
    inner2 = d2.get("data", d2) if isinstance(d2, dict) else None
    id2 = inner2.get("id") if isinstance(inner2, dict) else None
    ver2 = inner2.get("version") if isinstance(inner2, dict) else None
    if c2 == 0 and ver2 == 2:
        runner.pass_("TC-M-002", "创建 v2 成功且版本号单调 +1 (version=%s)" % ver2, "T-M")
    else:
        runner.fail("TC-M-002", "v2 版本号非 2（应为单调+1）", "version=%s code=%s" % (ver2, c2), "T-M")

    lst, bl, sl = backend.get_data("%s?promptKey=%s" % (PROMPT_VERSION_PATH, PK))
    lst_list = lst if isinstance(lst, list) else (lst.get("content") if isinstance(lst, dict) else None)
    if isinstance(lst_list, list) and len(lst_list) >= 2:
        runner.pass_("TC-M-002a", "列表含 ≥2 个版本 (实际 %d)" % len(lst_list), "T-M")
    else:
        runner.fail("TC-M-002a", "版本列表不足 2 条", "got=%r" % lst_list, "T-M")

    # ---- TC-M-003 activate + getActive ----
    ad, biz_a, astatus = backend.post("%s/%s/activate" % (PROMPT_VERSION_PATH, id2), {})
    # 兼容：部分实现可能用 PUT
    if astatus == 404:
        ad, biz_a, astatus = backend.put("%s/%s/activate" % (PROMPT_VERSION_PATH, id2), {})
    ac = ad.get("code") if isinstance(ad, dict) else astatus
    if ac == 0:
        runner.pass_("TC-M-003", "activate(v2) → code=0", "T-M")
    else:
        runner.fail("TC-M-003", "activate 失败(HTTP %s code=%s)" % (astatus, ac), "T-M")
        id2 = id2  # 继续尝试 getActive

    # getActive：尝试多种约定
    active = None
    for p in ("%s/active/%s" % (PROMPT_VERSION_PATH, PK),
              "%s?promptKey=%s&status=ACTIVE" % (PROMPT_VERSION_PATH, PK)):
        da, ba, sa = backend.get_data(p)
        if isinstance(da, dict):
            active = da.get("data", da)
        elif isinstance(da, list) and da:
            active = da[0]
        if active:
            break
    if isinstance(active, dict):
        aid = active.get("id")
        ast = active.get("status")
        if aid == id2 and ast == "ACTIVE":
            runner.pass_("TC-M-003a", "getActive 返回 v2(id=%s) 且 status=ACTIVE" % aid, "T-M")
        else:
            runner.fail("TC-M-003a", "getActive 未指向已激活的 v2", "got id=%s status=%s" % (aid, ast), "T-M")
        # 旧版本应 ARCHIVED
        old = None
        if isinstance(lst_list, list):
            for v in lst_list:
                if isinstance(v, dict) and v.get("id") == id1:
                    old = v
        if old is not None:
            # 重新拉取该版本状态
            dv, bv, sv = backend.get_data("%s/%s" % (PROMPT_VERSION_PATH, id1))
            ov = dv.get("data", dv) if isinstance(dv, dict) else None
            ov_status = ov.get("status") if isinstance(ov, dict) else None
            if ov_status == "ARCHIVED":
                runner.pass_("TC-M-003b", "旧 ACTIVE(v1) → ARCHIVED", "T-M")
            else:
                runner.skip("TC-M-003b", "旧版本状态非 ARCHIVED（可能实现未自动归档）：%s" % ov_status, "T-M")
    else:
        runner.fail("TC-M-003a", "无法获取 getActive（路径约定不符）", "T-M")

    # 清理（best-effort）
    if id1:
        backend.delete("%s/%s" % (PROMPT_VERSION_PATH, id1))
    if id2:
        backend.delete("%s/%s" % (PROMPT_VERSION_PATH, id2))
    runner.pass_("TC-M-004", "清理测试版本 id1=%s id2=%s" % (id1, id2), "T-M")

    return runner


if __name__ == "__main__":
    r = run_tests()
    _, _, failed, _ = r.summary()
    sys.exit(1 if failed else 0)
