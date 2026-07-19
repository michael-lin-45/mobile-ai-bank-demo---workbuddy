#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
run_all.py — 可观测 V4 测试统一入口（obs_v4_tests）

按依赖拓扑顺序执行各任务模块，汇总结果。失败不中断后续独立模块。

用法：
  python run_all.py                  # 跑全部（需 backend+collector+core 已起且已 seed）
  python run_all.py --seed          # 跑前先执行 test/seed_all.py 造数
  python run_all.py --quick         # 跳过耗时的告警 firing/suppression 生命周期（ALERT_FULL=0）
  python run_all.py --modules insights,alert   # 只跑指定模块
  python run_all.py --list           # 列出可用模块
  BACKEND_URL=http://10.0.0.5:9090 python run_all.py

前置：pip install requests
依赖：后端 9090 已起；Collector 4318、Core 8080、Redis 6379（部分用例需要）
"""
import os
import sys
import time
import subprocess
import argparse

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from common import ApiClient, REPO_ROOT, SEED_IF_EMPTY

# 模块执行顺序（见用例文档 §9 执行顺序与依赖）
MODULES = [
    "test_collector",   # T-C 独立，可最先
    "test_sessions",    # T-D 独立
    "test_dead_keys",   # T-E 独立
    "test_insights",    # T-A（含 T-L 边界）
    "test_alert",       # T-B → T-F
    "test_frontend",    # T-G/H/I（依赖 T-A）
    "test_metrics",     # T-K + T-J
    "test_prompt_version",  # T-M
    "test_reroute",     # T-N
    "test_rag",         # T-RAG：RAG 可观测埋点层端到端（需 Core dev + observability.rag.reference.enabled=true）
]


def run_seed():
    seed_script = os.path.join(REPO_ROOT, "test", "seed_all.py")
    if not os.path.exists(seed_script):
        print("[run_all] 未找到 seed_all.py：%s" % seed_script)
        return
    print("[run_all] 运行 seed_all.py 造数 ...")
    try:
        subprocess.run([sys.executable, seed_script], check=False,
                       cwd=REPO_ROOT, timeout=600)
        print("[run_all] seed 完成，等待 20s 让 spans 沉淀 ...")
        time.sleep(20)
    except Exception as e:  # noqa
        print("[run_all] seed 失败：%s" % e)


def main():
    ap = argparse.ArgumentParser(description="可观测 V4 测试统一入口")
    ap.add_argument("--seed", action="store_true", help="跑前执行 seed_all.py")
    ap.add_argument("--quick", action="store_true", help="跳过耗时告警生命周期(ALERT_FULL=0)")
    ap.add_argument("--modules", type=str, default="", help="逗号分隔模块名子集，如 insights,alert")
    ap.add_argument("--list", action="store_true", help="列出可用模块并退出")
    args = ap.parse_args()

    if args.list:
        print("可用模块：")
        for m in MODULES:
            print("  - %s" % m)
        return 0

    if args.quick:
        os.environ["ALERT_FULL"] = "0"

    if args.seed:
        run_seed()
    elif SEED_IF_EMPTY:
        # ensure_seeded 会在各模块内按需补 seed
        pass

    selected = MODULES
    if args.modules:
        wanted = [x.strip() for x in args.modules.split(",") if x.strip()]
        selected = [m for m in MODULES if m.replace("test_", "") in wanted or m in wanted]

    backend = ApiClient()

    overall = {"total": 0, "passed": 0, "failed": 0, "skipped": 0}
    t0 = time.time()
    print("=" * 64)
    print("  可观测 V4 测试套件  (BACKEND_URL=%s)" % backend.base)
    print("  模块：%s" % ", ".join(selected))
    print("=" * 64)

    for mod_name in selected:
        __import__(mod_name)
        mod = sys.modules[mod_name]
        runner = mod.run_tests(backend=backend)
        tot, pas, fai, skp = runner.summary()
        overall["total"] += tot
        overall["passed"] += pas
        overall["failed"] += fai
        overall["skipped"] += skp

    elapsed = time.time() - t0
    print("\n" + "=" * 64)
    print("  总计：%d  PASS：%d  FAIL：%d  SKIP：%d"
          % (overall["total"], overall["passed"], overall["failed"], overall["skipped"]))
    print("  耗时：%.1fs" % elapsed)
    print("=" * 64)
    if overall["failed"] > 0:
        print("  结论：存在 FAIL，验收未通过。")
        return 1
    print("  结论：无 FAIL（SKIP 项见人工/日志核查清单）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
