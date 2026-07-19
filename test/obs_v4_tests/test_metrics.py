#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_metrics.py — T-K P6 集中式指标注册表 + T-J P7 UpDownCounter pending_approval

覆盖详细设计 §3.1（MetricsRegistry / deepflux.* 前缀）与 §4.5（AddUpDown pending_approval）。

验证口径（ADR-2026-07-19 决策 5 / D-5）：
  不再 scrape /actuator/prometheus（micrometer-registry-prometheus 已移除，该端点 404）。
  改为验「Core Micrometer 注册表确实注册了该产品 meter」——即 GET Core 内置
  /actuator/metrics（Spring Boot Actuator，返回 JSON {"names":[...]}，无需 prometheus 依赖），
  或验 Backend 经 OTLP 收到该 meter。「或」关系二选一即可；本用例聚焦 Core 侧 actuator/metrics。

  注意：
   - 指标名在 /actuator/metrics 中为【点号命名】（如 deepflux.workflow.pending_approval、
     agent.intent.accuracy、gen_ai.client.operation.duration），不是 prometheus 的下划线。
   - 业务指标在 Core（8080）而非 backend（9090），故探测指向 CORE_URL。
   - 直接用 ApiClient 默认 application/json 读取，不再发送 Accept: text/plain 凑 prometheus。

可 API 验证部分：
  - TC-K-001 存在 deepflux.<domain>.<measure> 前缀指标（点号）
  - TC-K-002 存量 agent.*/gen_ai.* 仍可用（向后兼容，点号）
  - TC-J-001 存在 deepflux.workflow.pending_approval 指标（点号）；并可进一步 GET 单 meter 确认 value 字段
若 actuator/metrics 端点未暴露（非 200）或为空，用例 SKIP 并给人工/代码核查口径，不假通过。
T-J-002（+1/-1 实时语义）属代码+日志核查（见 README / 用例文档 §11），保持 SKIP。

运行：python test_metrics.py   （需 Core 起在 8080；backend 在 9090 可选）
"""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from common import ApiClient, TestRunner, CORE_URL


def _get_json(client, path):
    """GET path，返回 (obj_or_None, status_code)。业务指标在 Core，故探测指向 CORE_URL。

    使用 ApiClient 默认 application/json 直接读 Spring Boot Actuator /actuator/metrics；
    不再发送 Accept: text/plain 去凑 prometheus（该依赖已移除，/actuator/prometheus 返回 404）。
    """
    try:
        raw, status, _ = client.get(path)
        if status == 200 and isinstance(raw, dict):
            return raw, status
        # 非 200 或响应非 JSON 时一并视为不可用
        return None, status
    except Exception:  # noqa
        return None, 0


def run_tests(backend=None, runner=None):
    # T-K/T-J 业务指标在 Core，故探测指向 CORE_URL（不查 backend 9090 的原始业务指标）
    core = ApiClient(CORE_URL)
    runner = runner or TestRunner("T-K/T-J 指标注册表")
    print("\n--- T-K/T-J: 集中式注册表 + UpDownCounter ---")

    # 探测 actuator/metrics（ADR 决策5：验 Core Micrometer 注册表确有该 meter，
    # 不再 scrape /actuator/prometheus）
    metrics_json, mstatus = _get_json(core, "/actuator/metrics")
    names = []
    if mstatus == 200 and metrics_json:
        names = metrics_json.get("names", []) or []

    if mstatus != 200 or not names:
        # 端点未暴露（非 200）或返回空，SKIP 并给人工/代码核查口径，不假通过
        reason = "actuator/metrics 未暴露(status=%s)或为空" % mstatus
        runner.skip("TC-K-001", reason + "：改人工/代码核查 grep MetricsRegistry.java 确认 deepflux.* 前缀", "T-K")
        runner.skip("TC-K-002", reason + "：确认 agent.*/gen_ai.* 存量不动", "T-K")
        runner.skip("TC-J-001", reason + "：确认 Core 注入 metricsRegistry.addUpDown('workflow.pending_approval', ±1)", "T-J")
        runner.skip("TC-J-002", "人工核查：Core 触发 interrupt→approve，观测 pending_approval 经 OTLP 推送 +1/-1", "T-J")
        return runner

    nameset = set(names)

    # ---- TC-K-001 deepflux 前缀（点号命名）----
    deep = [n for n in names if n.startswith("deepflux.")]
    if deep:
        runner.pass_("TC-K-001",
                     "存在 deepflux.* 前缀指标（%d 个，例: %s）" % (len(deep), deep[0]),
                     "T-K")
    else:
        runner.fail("TC-K-001", "未发现任何 deepflux.* 前缀指标（P6 注册表未生效或未暴露）",
                    "样本指标数=%d" % len(names), "T-K")

    # ---- TC-K-002 存量 agent.*/gen_ai.* 兼容（点号命名，撤销 prometheus 下划线 hack）----
    # 统一为点号匹配：agent. 前缀 与 gen_ai. 前缀；不再兼容 prometheus 的下划线(agent_/gen_ai_/llm_)。
    agent_hits = [n for n in names if n.startswith("agent.")]
    genai_hits = [n for n in names if n.startswith("gen_ai.")]
    if agent_hits and genai_hits:
        runner.pass_("TC-K-002",
                     "存量 agent.*(%d)/gen_ai.*(%d) 指标仍可用" % (len(agent_hits), len(genai_hits)),
                     "T-K")
    else:
        miss = []
        if not agent_hits:
            miss.append("agent.*")
        if not genai_hits:
            miss.append("gen_ai.*")
        runner.skip("TC-K-002", "未检测到存量指标(%s)（可能未埋点或端点口径不同）" % ",".join(miss), "T-K")

    # ---- TC-J-001 pending_approval（点号命名）----
    pa_name = "deepflux.workflow.pending_approval"
    if pa_name in nameset:
        # 进一步确认该 meter 确有 value 字段（空闲期应为 0.0）
        pa_json, pa_status = _get_json(core, "/actuator/metrics/" + pa_name)
        if pa_status == 200 and pa_json and "measurements" in pa_json:
            val = pa_json.get("measurements", [{}])[0].get("value")
            runner.pass_("TC-J-001",
                         "存在 pending_approval 指标（%s, value=%s）" % (pa_name, val),
                         "T-J")
        else:
            runner.pass_("TC-J-001", "存在 pending_approval 指标（%s）" % pa_name, "T-J")
    else:
        runner.fail("TC-J-001", "未发现 pending_approval 指标（P7 UpDownCounter 未暴露）",
                    "样本指标数=%d" % len(names), "T-J")

    # ---- TC-J-002 实时 +1/-1 语义（人工/代码核查，保持 SKIP）----
    # 新验证口径（ADR 决策5）：验 Core Micrometer 确有该 meter「或」Backend 收到 OTLP meter。
    # +1/-1 实时语义需真实 interrupt→approve 流量，非自动化可验，保持 SKIP。
    runner.skip("TC-J-002", "人工/代码核查：Core 触发 interrupt→approve，"
                            "观测 pending_approval（Gauge/UpDownCounter）经 OTLP 推送至 Backend「或」"
                            "Core Micrometer 确有该 meter 时 +1 后 -1", "T-J")

    return runner


if __name__ == "__main__":
    r = run_tests()
    _, _, failed, _ = r.summary()
    sys.exit(1 if failed else 0)
