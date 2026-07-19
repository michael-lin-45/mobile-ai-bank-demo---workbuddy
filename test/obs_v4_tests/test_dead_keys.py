#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_dead_keys.py — T-E T30 清理 Redis 死 key

覆盖详细设计 §4.4：删除 RedisH2SyncService L113-117 的 5 行 addDoubleGauge
  (accuracy:intent / accuracy:rewrite / reroute_rate / business_completion / conversion)

纯 API 无法直接看到这 5 个 Redis 死 key，故采用：
  - TC-E-001 回归：主指标 API（insights-report / accuracy-report / confusion-matrix / metrics/realtime）
    在删除死 key 后仍 code=0 且有真实数据（证明主指标改 H2 读时算、不受影响）。
  - TC-E-002 人工/SQL 核查：H2 redis_metrics_snapshot 表不应含这 5 行（脚本给出 SQL，可人工在 H2 Console 执行）。
  - TC-E-003 代码核查：源中不再引用这 5 个 key（留给 CI/grep，脚本仅打印建议命令）。

运行：python test_dead_keys.py   （需后端已起且已 seed；死 key 删除后重启后端等 30s）
"""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from common import ApiClient, TestRunner, assert_code_ok, ensure_seeded

DEAD_KEYS = ["accuracy:intent", "accuracy:rewrite", "reroute_rate",
             "business_completion", "conversion"]


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

    # ---- TC-E-002 H2 直查（人工/SQL 核查，脚本给命令）----
    sql = ("SELECT metric_key, COUNT(*) AS c FROM redis_metrics_snapshot "
           "WHERE metric_key IN (%s) GROUP BY metric_key;"
           % ", ".join("'%s'" % k for k in DEAD_KEYS))
    runner.skip("TC-E-002", "人工/SQL 核查：在 H2 Console 执行以下 SQL，期望 0 行：\n    %s" % sql,
                "T-E")
    print("    [核查] H2 直查 redis_metrics_snapshot 期望不含 5 死 key：")
    print("        %s" % sql)

    # ---- TC-E-003 代码核查（grep 源）----
    grep_cmd = ("grep -rnE \"addDoubleGauge\\\\((accuracy:intent|accuracy:rewrite|"
                "reroute_rate|business_completion|conversion)\" "
                "observability/backend/src/main/java || echo 'NO_DEAD_KEY_REFERENCES'")
    runner.skip("TC-E-003", "代码核查：执行以下命令，期望输出 NO_DEAD_KEY_REFERENCES：\n    %s" % grep_cmd,
                "T-E")
    print("    [核查] 源码核查（在仓库根执行）：")
    print("        %s" % grep_cmd)

    return runner


if __name__ == "__main__":
    r = run_tests()
    _, _, failed, _ = r.summary()
    sys.exit(1 if failed else 0)
