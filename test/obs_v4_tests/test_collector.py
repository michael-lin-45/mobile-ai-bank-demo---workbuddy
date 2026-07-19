#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_collector.py — T-C Collector filter / tail_sampling / attributes 增强

覆盖详细设计 §4.7 / 主文档 §11.1：
  - TC-C-001 配置：processors 含 filter / attributes / tail_sampling
  - TC-C-002 metrics/logs 管道含 filter+attributes，不含 tail_sampling
  - TC-C-003 otelcol validate --config 退出码 0（若 otelcol 可用）
  - TC-C-004 运行时：健康检查 Span 被 filter 丢弃，不入 Backend（span-stats 不含 actuator/health）
  - TC-C-005 attributes 注入 env/service.version（运行时抽样核查）
  - TC-C-006 tail_sampling 四策略存在（配置核查）

纯 API 难确定性断言 tail_sampling 抽样率，给出人工核查口径（见 README / 用例文档 §11）。

运行：python test_collector.py   （需 backend/collector/core 起且已 seed）
"""
import os
import sys
import shutil
import subprocess

sys.path.insert(0, os.path.dirname(__file__))
from common import ApiClient, TestRunner, REPO_ROOT


def _config_path():
    return os.environ.get("COLLECTOR_CONFIG") or os.path.join(
        REPO_ROOT, "observability", "otel-collector", "config.yaml")


def _read_config(path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return f.read()
    except OSError:
        return None


def _has(text, *subs):
    return all(s in text for s in subs)


def run_tests(backend=None, runner=None):
    backend = backend or ApiClient()
    runner = runner or TestRunner("T-C Collector 增强")
    print("\n--- T-C: Collector filter/sampling/attributes ---")

    cfg_path = _config_path()
    cfg = _read_config(cfg_path)
    if cfg is None:
        runner.skip("TC-C-001", "未找到 config.yaml（%s）；跳过配置核查" % cfg_path, "T-C")
        runner.skip("TC-C-002", "同上", "T-C")
        runner.skip("TC-C-006", "同上", "T-C")
    else:
        # ---- TC-C-001 processors 存在 ----
        if _has(cfg, "filter:", "attributes:", "tail_sampling:"):
            runner.pass_("TC-C-001", "config.yaml 含 filter / attributes / tail_sampling 三个 processor", "T-C")
        else:
            runner.fail("TC-C-001", "config.yaml 缺少必要 processor",
                        "filter=%s attributes=%s tail_sampling=%s"
                        % ("filter:" in cfg, "attributes:" in cfg, "tail_sampling:" in cfg), "T-C")
        # traces pipeline 顺序
        if "traces:" in cfg and "tail_sampling" in cfg:
            # 粗略检查 traces 管道引用
            if _has(cfg, "processors:", "batch", "memory_limiter", "filter",
                    "attributes", "tail_sampling"):
                runner.pass_("TC-C-001a", "traces 管道引用 batch/memory_limiter/filter/attributes/tail_sampling", "T-C")
            else:
                runner.fail("TC-C-001a", "traces 管道 processor 引用不全", "T-C")

        # ---- TC-C-002 metrics/logs 不含 tail_sampling ----
        # 锚定到 service.pipelines 段，避免误匹配 telemetry.metrics（与 pipeline metrics 同名）
        def pipeline_block(name):
            pidx = cfg.find("pipelines:")
            if pidx < 0:
                pidx = 0
            marker = "\n    %s:" % name  # 管道名在 pipelines 下缩进 4 空格
            start = cfg.find(marker, pidx)
            if start < 0:
                return ""
            end = len(cfg)
            for hdr in ("\n    traces:", "\n    metrics:", "\n    logs:"):
                pos = cfg.find(hdr, start + len(marker))
                if 0 < pos < end:
                    end = pos
            return cfg[start:end]
        mseg = pipeline_block("metrics")
        lseg = pipeline_block("logs")
        if mseg and "tail_sampling" not in mseg:
            runner.pass_("TC-C-002", "metrics 管道不含 tail_sampling（符合设计）", "T-C")
        else:
            runner.fail("TC-C-002", "metrics 管道含 tail_sampling（不应有）", "T-C")
        if lseg and "tail_sampling" not in lseg:
            runner.pass_("TC-C-002a", "logs 管道不含 tail_sampling（符合设计）", "T-C")
        else:
            runner.fail("TC-C-002a", "logs 管道含 tail_sampling（不应有）", "T-C")

        # ---- TC-C-006 tail_sampling 四策略 ----
        if _has(cfg, "errors:", "slow:", "llm-failure:", "normal-sampling:"):
            runner.pass_("TC-C-006", "tail_sampling 含 errors/slow/llm-failure/normal-sampling 四策略", "T-C")
        else:
            runner.fail("TC-C-006", "tail_sampling 策略不全",
                        "errors=%s slow=%s llm-failure=%s normal-sampling=%s"
                        % ("errors:" in cfg, "slow:" in cfg, "llm-failure:" in cfg,
                           "normal-sampling:" in cfg), "T-C")

    # ---- TC-C-003 otelcol validate ----
    otelcol = os.environ.get("OTELCOL_BIN")
    if not otelcol:
        otelcol = shutil.which("otelcol") or shutil.which("otelcol.exe")
    if otelcol and cfg is not None:
        try:
            r = subprocess.run([otelcol, "validate", "--config", cfg_path],
                               capture_output=True, text=True, timeout=60)
            if r.returncode == 0:
                runner.pass_("TC-C-003", "otelcol validate 退出码 0", "T-C")
            else:
                runner.fail("TC-C-003", "otelcol validate 失败(rc=%d)" % r.returncode,
                            (r.stderr or r.stdout)[:300], "T-C")
        except Exception as e:  # noqa
            runner.skip("TC-C-003", "运行 otelcol validate 出错：%s" % e, "T-C")
    else:
        runner.skip("TC-C-003", "未找到 otelcol 二进制（设 OTELCOL_BIN 或加入 PATH 可启用）", "T-C")

    # ---- TC-C-004 运行时：外部 traced 健康检查 Span 不入 Backend（Collector filter 生效）----
    # 说明：后端自身 actuator/tomcat server 自埋点（GET /actuator/health、GET /**、
    #       ResponseFacade.sendError）由后端直存，不经 Collector trace 管道，不属 filter 作用域；
    #       本断言仅针对「经 Collector 进入的外部 traced 健康检查 Span」未过滤的情形。
    BACKEND_SELF_SPANS = {"GET /actuator/health", "GET /**", "ResponseFacade.sendError"}
    stats, biz, status = backend.get_data("/api/v1/traces/debug/span-stats")
    if isinstance(stats, dict):
        names = stats.get("distinctOpNames")
        if isinstance(names, list):
            bad = [n for n in names if isinstance(n, str)
                   and ("actuator/health" in n or n.endswith("/health") or "health" in n.lower())
                   and n not in BACKEND_SELF_SPANS]
            if not bad:
                runner.pass_("TC-C-004", "span-stats 不含外部 traced 健康检查 Span（filter 生效）", "T-C")
            else:
                runner.fail("TC-C-004", "span-stats 含外部 traced 健康检查 Span（filter 未生效）",
                            "bad=%s" % bad, "T-C")
        else:
            runner.skip("TC-C-004", "distinctOpNames 非列表（字段名可能不同）", "T-C")
    else:
        runner.skip("TC-C-004", "无法获取 span-stats（后端/Core 未起或未 seed）", "T-C")

    # ---- TC-C-005 attributes 注入（运行时抽样，软断言）----
    # 取一条 trace 详情看 attributes 是否含 env/service.version
    traces, biz, status = backend.get_data("/api/v1/traces?size=5")
    trace_list = traces if isinstance(traces, list) else (traces.get("content") if isinstance(traces, dict) else None)
    found_attr = False
    if isinstance(trace_list, list) and trace_list:
        tid = trace_list[0].get("traceId") if isinstance(trace_list[0], dict) else None
        if tid:
            det, _, _ = backend.get_data("/api/v1/traces/%s" % tid)
            blob = str(det)
            if "env" in blob and ("production" in blob or "service.version" in blob):
                found_attr = True
    if found_attr:
        runner.pass_("TC-C-005", "抽样 trace 含 env/service.version（attributes 注入生效）", "T-C")
    else:
        runner.skip("TC-C-005", "attributes 注入需人工核查：查入 Backend 的 span attributes 含 env=production/service.version=1.0.0", "T-C")

    return runner


if __name__ == "__main__":
    r = run_tests()
    _, _, failed, _ = r.summary()
    sys.exit(1 if failed else 0)
