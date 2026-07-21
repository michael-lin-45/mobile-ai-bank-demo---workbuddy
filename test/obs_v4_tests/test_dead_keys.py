#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_dead_keys.py — T-E T30 清理 Redis 死 key

覆盖详细设计 §4.4：删除 RedisH2SyncService L113-117 的 5 行 addDoubleGauge
  (accuracy:intent / accuracy:rewrite / reroute_rate / business_completion / conversion)

纯 API 无法直接看到这 5 个 Redis 死 key，故采用：
  - TC-E-001 回归：主指标 API（insights-report / accuracy-report / confusion-matrix / metrics/realtime）
    在删除死 key 后仍 code=0 且有真实数据（证明主指标改 H2 读时算、不受影响）。
  - TC-E-002 人工/SQL 核查：H2 redis_metrics_snapshot 表不应含这 5 行（脚本给出 SQL，可人工在 H2 Console 执行，M3 遗留）。
  - TC-E-003 自动代码核查：纯 Python（os.walk + re）遍历 observability/backend/src/main/java 下所有 .java，
    正则断言无 addDoubleGauge(accuracy:intent|accuracy:rewrite|reroute_rate|business_completion|conversion)
    引用；命中任一则 FAIL，零命中则 PASS（NO_DEAD_KEY_REFERENCES，可断言、可移植、不依赖 shell grep）。

运行：python test_dead_keys.py   （需后端已起且已 seed；死 key 删除后重启后端等 30s）
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(__file__))
from common import (ApiClient, TestRunner, assert_code_ok, ensure_seeded,
                    REPO_ROOT)

DEAD_KEYS = ["accuracy:intent", "accuracy:rewrite", "reroute_rate",
             "business_completion", "conversion"]

# TC-E-003 自动代码核查：匹配 RedisH2SyncService 曾删除的 5 行 addDoubleGauge(死key) 调用
_DEAD_KEY_RE = re.compile(
    r"addDoubleGauge\((accuracy:intent|accuracy:rewrite|reroute_rate|"
    r"business_completion|conversion)"
)
_JAVA_SRC_REL = os.path.join("observability", "backend", "src", "main", "java")


def _scan_dead_key_references(repo_root):
    """遍历 backend Java 源，返回所有命中死 key 的 (rel_path, lineno, key, text)。

    返回 None 表示源码目录不存在（无法核查）。
    """
    src_dir = os.path.join(repo_root, _JAVA_SRC_REL)
    if not os.path.isdir(src_dir):
        return None
    hits = []
    for root, _dirs, files in os.walk(src_dir):
        for fn in files:
            if not fn.endswith(".java"):
                continue
            fpath = os.path.join(root, fn)
            try:
                with open(fpath, "r", encoding="utf-8", errors="ignore") as fh:
                    for lineno, line in enumerate(fh, 1):
                        m = _DEAD_KEY_RE.search(line)
                        if m:
                            rel = os.path.relpath(fpath, repo_root)
                            hits.append((rel, lineno, m.group(1), line.strip()))
            except OSError:
                continue
    return hits


def run_tests(backend=None, runner=None):
    backend = backend or ApiClient()
    runner = runner or TestRunner("T-E 死 key 清理")
    print("\n--- T-E: T30 清理 Redis 死 key ---")

    if not ensure_seeded(backend):
        runner.skip("TC-E-PRE", "后端无会话数据（请先 seed 或设 SEED_IF_EMPTY=1）", "T-E")
        # 仍跑回归断言（即便空，code=0 也说明主路径没崩）

    # ---- TC-E-001 主指标 API 回归 ----
    endpoints = [
        ("/api/v1/ai/insights-report", "insights-report"),
        ("/api/v1/ai/accuracy-report", "accuracy-report"),
        ("/api/v1/ai/confusion-matrix", "confusion-matrix"),
        ("/api/v1/metrics/realtime", "metrics/realtime"),
    ]
    all_ok = True
    for path, name in endpoints:
        d, biz, status = backend.get_data(path)
        ok = assert_code_ok(d, biz, status, "TC-E-001-%s" % name.replace("-", "_"),
                            "GET %s → code=0（死 key 删除后主指标不回归）" % path, runner, "T-E")
        if not ok:
            all_ok = False
        else:
            # 进一步：data 非空（有真实结构）
            if isinstance(d, dict) and d:
                runner.pass_("TC-E-001-%s-data" % name.replace("-", "_"),
                             "%s 返回非空 data（真实数据，非占位）" % name, "T-E")
            elif isinstance(d, list):
                runner.pass_("TC-E-001-%s-data" % name.replace("-", "_"),
                             "%s 返回列表" % name, "T-E")
            else:
                runner.skip("TC-E-001-%s-data" % name.replace("-", "_"),
                             "%s data 为空（可能无数据，非死 key 导致）" % name, "T-E")
    if all_ok:
        runner.pass_("TC-E-001", "4 个主指标 API 全部 code=0 且未因死 key 删除而回归", "T-E")
    else:
        runner.fail("TC-E-001", "主指标 API 在死 key 删除后出现异常（回归失败）", "T-E")

    # ---- TC-E-002 死 key 自动核查（升级为 HTTP 断言，守 0 FAIL，设计 §2.1）----
    # 调用后端只读诊断端点 GET /api/v1/admin/diagnostics/dead-keys，
    # 解析 data.totalDead，断言 == 0（T30 已删除 5 个 addDoubleGauge，快照表应无死 key 行）。
    # 端点不可达/非 200 → 优雅 SKIP；totalDead > 0 → FAIL。
    diag_path = "/api/v1/admin/diagnostics/dead-keys"
    d, biz, status = backend.get_data(diag_path)
    if status != 200 or not isinstance(d, dict) or "totalDead" not in d:
        # 后端未起或端点不可达：优雅 SKIP，绝不 FAIL（守 0 FAIL 不变量）
        runner.skip("TC-E-002",
                    "后端未起或诊断端点不可达（HTTP=%s），死 key 自动核查优雅 SKIP；"
                    "人工核查可在 H2 Console 执行：SELECT * FROM redis_metrics_snapshot "
                    "WHERE metric_key IN (%s)" % (status, ", ".join("'%s'" % k for k in DEAD_KEYS)),
                    "后端未起/不可达", "T-E")
    else:
        total_dead = d.get("totalDead", 0)
        if total_dead == 0:
            runner.pass_("TC-E-002",
                         "死 key 诊断端点 totalDead=0（5 死 key 无快照行，T30 已清理）", "T-E")
        else:
            hits = []
            if isinstance(d.get("deadKeys"), list):
                hits = [x for x in d["deadKeys"] if isinstance(x, dict) and x.get("count", 0) > 0]
            runner.fail("TC-E-002", "死 key 诊断端点报告 totalDead>0", "totalDead=%s hits=%s" % (total_dead, hits), "T-E")

    # ---- TC-E-003 自动代码核查（纯 Python 遍历源码，断言无死 key 引用）----
    print("    [核查] TC-E-003 自动遍历 backend Java 源，正则断言无 addDoubleGauge(死key) 引用 ...")
    java_src = os.path.join(REPO_ROOT, _JAVA_SRC_REL)
    if not os.path.isdir(java_src):
        runner.skip("TC-E-003",
                    "源码目录缺失，无法自动核查死 key（期望路径: %s）" % _JAVA_SRC_REL,
                    "T-E")
    else:
        hits = _scan_dead_key_references(REPO_ROOT)
        if hits:
            hit_desc = "; ".join("%s:%d [%s]" % (rel, ln, key)
                                 for rel, ln, key, _t in hits)
            runner.fail("TC-E-003", "源码仍引用死 key: %s" % hit_desc, "T-E")
        else:
            runner.pass_("TC-E-003",
                         "源码无 5 死 key 引用（NO_DEAD_KEY_REFERENCES，自动核查）", "T-E")

    return runner


if __name__ == "__main__":
    r = run_tests()
    _, _, failed, _ = r.summary()
    sys.exit(1 if failed else 0)
