#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
总览大屏 8 项修复 验收脚本
===================================================
前置:
  - 后端已用「新 jar」重启 (start-all.ps1 会自动 mvn package + 重启)
  - Core(8080) / Backend(9090) / Frontend(3000) 在运行

用法:
  python verify_dashboard.py                      # 仅验收（假设新 jar 已生效）
  python verify_dashboard.py --purge              # 验收前先清 H2 老数据(只留 2 天)
  python verify_dashboard.py --traffic            # 验收前先打一轮 L2 流量(生成 TTFT/Token)
  python verify_dashboard.py --purge --traffic --wait 120

8 项验收点:
  #1 清 H2 老数据只留 2 天      -> purge 接口返回成功 + 删除计数
  #2 首 Token 时延非 0          -> ttftP50/P95/P99 > 0 (ms)
  #3 P95 系统时延非 0           -> p95Latency > 0 (ms)
  #4 错误率 2 位小数            -> errorRate 为分数且前端显示 toFixed(2)
  #5 业务转化率「暂无」         -> conversionRate == null
  #6 访问用户量=DAU             -> dau 非空且 > 0
  #7 实时在线非 0               -> realTimeOnline 非空(流量后 > 0)
  #8 趋势图例有数据             -> /trend 返回 times/requests/tokens 非空且合计 > 0
"""
import urllib.request
import urllib.error
import json
import sys
import time

B = "http://127.0.0.1:9090"
C = "http://127.0.0.1:8080"

CORE_CHAT = C + "/api/bank/chat"

TRAFFIC_TURNS = [
    ("vSeedA", "转账50000给李四"),
    ("vSeedA", "确认"),
    ("vSeedB", "查一下上个月的账单"),
    ("vSeedC", "推荐稳健的基金"),
    ("vSeedC", "解释一下朝朝盈"),
    ("vSeedD", "我想看看理财"),
    ("vSeedE", "推荐科技板块的基金"),
    ("vSeedF", "你好"),
]


def get(url, timeout=8):
    try:
        req = urllib.request.Request(url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode()), r.status
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode()), e.code
        except Exception:
            return {"_error": str(e)}, e.code
    except Exception as e:
        return {"_error": str(e)}, 0


def post(url, timeout=60):
    try:
        req = urllib.request.Request(url, data=b"", headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode()), r.status
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode()), e.code
        except Exception:
            return {"_error": str(e)}, e.code
    except Exception as e:
        return {"_error": str(e)}, 0


def send_traffic():
    print("\n[流量] 向 Core 发送 L2 流量 ...")
    for sid, msg in TRAFFIC_TURNS:
        body = json.dumps({"message": msg}).encode()
        url = CORE_CHAT + "?sessionId=" + sid
        try:
            req = urllib.request.Request(url, data=body,
                                         headers={"Content-Type": "application/json"}, method="POST")
            with urllib.request.urlopen(req, timeout=60) as r:
                d = json.loads(r.read().decode())
                print(f"  [{d.get('status')}] intent={d.get('intent')} - {(d.get('content') or '')[:40]}")
        except Exception as e:
            print(f"  ERR {sid}/{msg[:20]}: {e}")
        time.sleep(2)
    print("  [流量] 完成。等待 OTel 上报 + Redis->H2 快照 (~60s) ...")


def main():
    do_purge = "--purge" in sys.argv
    do_traffic = "--traffic" in sys.argv
    wait = 60
    for a in sys.argv:
        if a.startswith("--wait="):
            wait = int(a.split("=", 1)[1])

    results = []  # (idx, name, ok, detail)

    # ── 预检: 必须加载「新 jar」(含 /trend 且 conversionRate=null) ──
    trend_pre, code = get(B + "/api/v1/metrics/trend?hours=6")
    td_pre = trend_pre.get("data", trend_pre) if isinstance(trend_pre, dict) else {}
    new_jar = (code == 200 and isinstance(td_pre.get("times"), list))
    if not new_jar:
        rt0, _ = get(B + "/api/v1/metrics/realtime")
        d0 = rt0.get("data", rt0) if isinstance(rt0, dict) else {}
        cr0 = d0.get("conversionRate")
        print("=" * 60)
        print("✗ 预检失败: 后端仍在运行旧 jar, 新修复未生效")
        print("  /trend 返回 HTTP %s (旧 jar 无此端点)" % code)
        if cr0 is not None:
            print("  realtime.conversionRate=%s (旧 jar 返回 100.0; 新 jar 应返回 null)" % cr0)
        print("  请在 PowerShell 重跑启动脚本以部署新 jar:")
        print("    .\\scripts\\start-all.ps1")
        print("  它会停掉旧 backend(释放 jar 锁) → mvn package → 重启。")
        print("=" * 60)
        sys.exit(2)
    print("[预检] 后端已加载新 jar (/trend 可用, HTTP %d)" % code)

    # ── #1 清 H2 老数据 ──
    if do_purge:
        print("\n[#1] 清理 H2 老数据 (保留最近 2 天) ...")
        pr, pcode = post(B + "/api/v1/admin/purge?days=2")
        if pcode == 200 and isinstance(pr, dict) and pr.get("code", -1) == 0:
            d = pr.get("data", pr)
            del_spans = d.get("deletedSpans")
            del_metrics = d.get("deletedMetricsAgg")
            del_snap = d.get("deletedSnapshots")
            del_logs = d.get("deletedLogs")
            msg = "已清理 | spans=%s metrics_agg=%s snapshot=%s logs=%s" % (
                del_spans, del_metrics, del_snap, del_logs)
            # 任一表删除计数=-1 表示 bulk delete 抛异常未生效，必须判 FAIL（防止“假绿”）
            if -1 in (del_spans, del_metrics, del_snap, del_logs):
                results.append(("1", "清 H2 老数据(只留2天)", False,
                                "删除未生效(计数=-1，bulk delete 失败): " + msg))
                print("  ✗ " + msg + " (删除未生效!)")
            else:
                results.append(("1", "清 H2 老数据(只留2天)", True, msg))
                print("  ✓ " + msg)
        else:
            results.append(("1", "清 H2 老数据(只留2天)", False,
                            "purge 返回 HTTP %d: %s" % (pcode, json.dumps(pr, ensure_ascii=False)[:200])))
            print("  ✗ purge 失败 HTTP %d" % pcode)
    else:
        results.append(("1", "清 H2 老数据(只留2天)", None, "未执行 (加 --purge 执行清理)"))
        print("\n[#1] 跳过清理 (如需清理请加 --purge)")

    # ── 流量 ──
    if do_traffic:
        send_traffic()

    # ── 等待 TTFT / P95 指标落地 ──
    print("\n[等待] 轮询 /realtime 直到 TTFT 与 P95 时延非 0 (最多 %ds) ..." % wait)
    rt = None
    deadline = time.time() + wait
    while time.time() < deadline:
        rt, _ = get(B + "/api/v1/metrics/realtime")
        data = rt.get("data", rt) if isinstance(rt, dict) else {}
        if data.get("ttftP95", 0) and data.get("p95Latency", 0):
            print("  ✓ 指标已落地 (ttftP95=%s, p95Latency=%s)" % (data.get("ttftP95"), data.get("p95Latency")))
            break
        time.sleep(5)
    else:
        print("  ! 超时: TTFT/P95 仍为 0, 继续验收(可能无流量或 Redis 快照未就绪)")

    if rt is None:
        rt, _ = get(B + "/api/v1/metrics/realtime")
    data = rt.get("data", rt) if isinstance(rt, dict) else {}

    # ── #2 首 Token 时延 非 0 ──
    ttft = (data.get("ttftP50", 0) or 0, data.get("ttftP95", 0) or 0, data.get("ttftP99", 0) or 0)
    # 阈值 >=1: 新 jar 单位为 ms(普遍 >1); 旧 jar 为 0
    ok2 = all(v >= 1 for v in ttft)
    results.append(("2", "首Token时延非0(ms)", ok2,
                    "P50=%s P95=%s P99=%s (ms)" % ttft))
    print("\n[#2] 首 Token 时延: P50=%s P95=%s P99=%s %s" % (ttft[0], ttft[1], ttft[2], "✓" if ok2 else "✗"))

    # ── #3 P95 系统时延 非 0 ──
    p95 = data.get("p95Latency", 0) or 0
    # 阈值 >=1: 新 jar 单位为 ms(如 131); 旧 jar 为秒(0.131)→ 前端 Math.round=0ms
    ok3 = p95 >= 1.0
    results.append(("3", "P95系统时延非0(ms)", ok3, "p95Latency=%s (前端 Math.round → %dms)" % (p95, round(p95))))
    print("[#3] P95 系统时延: %s → 前端显示 %dms %s" % (p95, round(p95), "✓" if ok3 else "✗"))

    # ── #4 错误率 2 位小数 ──
    er = data.get("errorRate")
    if isinstance(er, (int, float)) and er <= 1.0:
        disp = "%.2f" % (er * 100)
        ok4 = True
        detail4 = "分数=%s → 显示 %s%% (2位小数)" % (er, disp)
    elif er is None:
        ok4, detail4 = False, "errorRate 为 null"
    else:
        ok4, detail4 = False, "errorRate=%s 不是分数(疑似已是百分比)" % er
    results.append(("4", "错误率2位小数", ok4, detail4))
    print("[#4] 错误率: %s %s" % (detail4, "✓" if ok4 else "✗"))

    # ── #5 业务转化率 暂无 ──
    cr = data.get("conversionRate")
    ok5 = cr is None
    results.append(("5", "业务转化率=暂无", ok5, "conversionRate=%s (null→前端显示'暂无')" % cr))
    print("[#5] 业务转化率: conversionRate=%s %s" % (cr, "✓" if ok5 else "✗"))

    # ── #6 访问用户量 = DAU ──
    dau = data.get("dau")
    ok6 = isinstance(dau, (int, float)) and dau > 0
    results.append(("6", "访问用户量=DAU", ok6, "dau=%s (历史统计人数)" % dau))
    print("[#6] 访问用户量: dau=%s %s" % (dau, "✓" if ok6 else "✗"))

    # ── #7 实时在线 非 0 ──
    online = data.get("realTimeOnline")
    ok7 = online is not None and online >= 0 and (online > 0 or not do_traffic)
    note7 = "realTimeOnline=%s" % online
    if do_traffic and (online is None or online == 0):
        note7 += " (流量后仍 0 → 检查 SessionService 滑动窗口/在线 TTL)"
        ok7 = False
    results.append(("7", "实时在线非0", ok7, note7))
    print("[#7] 实时在线: %s %s" % (note7, "✓" if ok7 else "✗"))

    # ── #8 趋势图例有数据 ──
    trend, tcode = get(B + "/api/v1/metrics/trend?hours=6")
    td = trend.get("data", trend) if isinstance(trend, dict) else {}
    times = td.get("times") or []
    reqs = td.get("requests") or []
    toks = td.get("tokens") or []
    req_sum = sum(x for x in reqs if isinstance(x, (int, float)))
    tok_sum = sum(x for x in toks if isinstance(x, (int, float)))
    ok8 = len(times) > 0 and req_sum > 0 and tok_sum > 0
    results.append(("8", "趋势图例有数据", ok8,
                    "buckets=%d 请求合计=%d Token合计=%d" % (len(times), req_sum, tok_sum)))
    print("[#8] 趋势: buckets=%d 请求合计=%d Token合计=%d %s" % (len(times), req_sum, tok_sum, "✓" if ok8 else "✗"))

    # ── 汇总 ──
    print("\n" + "=" * 60)
    print("验收汇总")
    print("=" * 60)
    npass = sum(1 for _, _, ok, _ in results if ok is True)
    nfail = sum(1 for _, _, ok, _ in results if ok is False)
    nskip = sum(1 for _, _, ok, _ in results if ok is None)
    for idx, name, ok, detail in results:
        mark = "✓ PASS" if ok is True else ("✗ FAIL" if ok is False else "• SKIP")
        print("  [%s] #%s %s — %s" % (mark, idx, name, detail))
    print("-" * 60)
    print("通过 %d / 失败 %d / 跳过 %d" % (npass, nfail, nskip))
    print("=" * 60)
    sys.exit(1 if nfail > 0 else 0)


if __name__ == "__main__":
    main()
