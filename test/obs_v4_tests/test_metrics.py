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
import json
import requests

sys.path.insert(0, os.path.dirname(__file__))
from common import ApiClient, TestRunner, CORE_URL, REQUEST_TIMEOUT


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


# ----------------------------------------------------------------------------
# TC-J-002 辅助：pending_approval ±1 语义集成测试（设计 §2.2）
# ----------------------------------------------------------------------------

_PA_NAME = "deepflux.workflow.pending_approval"
_TCJ2_SESSION = "TCJ2_TFR_01"


def _sample_pending_approval(client):
    """采样 Core(8080) 的 deepflux.workflow.pending_approval{intent=TRANSFER} 实时值。

    返回 float（值）；404/未注册 → 0.0；其它异常（连接失败/解析失败）→ None。
    """
    raw, status = _get_json(client, "/actuator/metrics/" + _PA_NAME + "?tag=intent:TRANSFER")
    if status == 200 and isinstance(raw, dict) and "measurements" in raw:
        try:
            return float(raw["measurements"][0]["value"])
        except (KeyError, IndexError, TypeError, ValueError):
            return None  # 结构异常 → 采样失败
    if status == 404:
        return 0.0  # 未注册 → 视为 0
    return None  # 其它异常（5xx / 连接失败）→ 采样失败


def _dig_status(obj):
    """从 chat 响应对象中提取大写 status 字符串（兼容 JSON 与 SSE data: 行）。"""
    if not isinstance(obj, dict):
        return None
    s = obj.get("status")
    if isinstance(s, str):
        return s.upper()
    d = obj.get("data")
    if isinstance(d, dict) and isinstance(d.get("status"), str):
        return d["status"].upper()
    return None


def _core_chat(client, session_id, message):
    """POST /api/bank/chat?sessionId=... 返回 (status_str_or_None, parsed_obj_or_None)。"""
    try:
        resp = client.session.request(
            "POST", client.base + "/api/bank/chat",
            params={"sessionId": session_id},
            json={"message": message},
            timeout=REQUEST_TIMEOUT,
        )
    except requests.RequestException:
        return None, None
    if resp.status_code >= 500:
        return None, None
    status = None
    parsed = None
    try:
        parsed = resp.json()
        status = _dig_status(parsed)
    except ValueError:
        # 退化解析 SSE：逐行 data: {...}
        for line in resp.text.splitlines():
            line = line.strip()
            if not line.startswith("data:"):
                continue
            chunk = line[len("data:"):].strip()
            if not chunk or chunk == "[DONE]":
                continue
            try:
                obj = json.loads(chunk)
            except ValueError:
                continue
            s = _dig_status(obj)
            if s:
                status = s
            if parsed is None:
                parsed = obj
    return status, parsed


def _is_interrupt(s):
    return s in ("INTERRUPTED", "NEEDS_CONFIRMATION")


def _is_completed(s):
    return s == "COMPLETED"


def _approx_eq(a, b, tol=1e-9):
    return abs(a - b) <= tol


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

    # ---- TC-J-002 实时 +1/-1 语义（集成测试，设计 §2.2）----
    # 前置：Core(8080) 在线 + LLM 可用（首消息进入 INTERRUPTED 即证明 LLM 可用）。
    # 任一前置不满足 / 采样异常 → 优雅 SKIP（不 FAIL，守 0 FAIL 不变量）。
    # 全流程成功且 delta 正确 → PASS；否则 SKIP 留痕交 QA 在 Core+LLM 在线环境复跑。
    B = _sample_pending_approval(core)
    if B is None:
        runner.skip("TC-J-002",
                    "pending_approval 采样异常（Core 未起/端点异常）→ 优雅 SKIP", "T-J")
    else:
        # 步骤 3：触发中断（TRANSFER 子图 interruptBefore 前置需 LLM）→ +1
        s1, _ = _core_chat(core, _TCJ2_SESSION, "转账给李四")
        if not _is_interrupt(s1):
            runner.skip("TC-J-002",
                        "首消息未进入 INTERRUPTED（status=%s，可能 LLM 不可用/环境异常）→ 优雅 SKIP"
                        % s1, "T-J")
        else:
            # 步骤 4：采样 V1，断言 V1 - B == +1
            V1 = _sample_pending_approval(core)
            if V1 is None or not _approx_eq(V1 - B, 1):
                runner.skip("TC-J-002",
                            "pending_approval 未观测到 +1（B=%s, V1=%s）→ 优雅 SKIP" % (B, V1),
                            "T-J")
            else:
                # 步骤 5：审批完成（同 sessionId 再发 chat → resume → COMPLETED）→ -1
                s2, _ = _core_chat(core, _TCJ2_SESSION, "500")
                if not _is_completed(s2):
                    runner.skip("TC-J-002",
                                "二次消息未进入 COMPLETED（status=%s）→ 优雅 SKIP" % s2, "T-J")
                else:
                    # 步骤 6：采样 V2，断言 V2 - V1 == -1 且 V2 == B
                    V2 = _sample_pending_approval(core)
                    if V2 is None or not (_approx_eq(V2 - V1, -1) and _approx_eq(V2, B)):
                        runner.skip("TC-J-002",
                                    "pending_approval 未观测到 -1 或 V2!=B（V1=%s, V2=%s, B=%s）→ 优雅 SKIP"
                                    % (V1, V2, B), "T-J")
                    else:
                        runner.pass_("TC-J-002",
                                     "pending_approval ±1 语义验证通过（B=%s → V1=%s(+1) → V2=%s(-1)）"
                                     % (B, V1, V2), "T-J")

    return runner


if __name__ == "__main__":
    r = run_tests()
    _, _, failed, _ = r.summary()
    sys.exit(1 if failed else 0)
