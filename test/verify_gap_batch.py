#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GAP 批量验证脚本（Core 遥测补齐后）
用法：
  1. 用 start-all.ps1 重启前后台服务（加载新 jar）
  2. 终端跑 bash test/seed.sh（或 seed_new.sh）触发 L2 流量
  3. 等 60 秒（OTLP metrics 步长）
  4. 跑 python3 test/verify_gap_batch.py
脚本只查询后端(9090)，不打流量。
"""
import urllib.request
import json
import datetime

BASE = "http://127.0.0.1:9090"


def get(path):
    try:
        with urllib.request.urlopen(BASE + path, timeout=20) as r:
            return json.loads(r.read().decode())
    except Exception as e:
        return {"_err": str(e)}


now = datetime.datetime.now(datetime.timezone.utc)
to = now.strftime("%Y-%m-%dT%H:%M:%SZ")
frm = (now - datetime.timedelta(hours=6)).strftime("%Y-%m-%dT%H:%M:%SZ")

rt = get("/api/v1/metrics/realtime")
data = rt.get("data", rt)

print("=== 1. realtime 实时卡片 ===")
for k in ["l0Calls", "l1Calls", "l2Calls", "intentAccuracy",
          "rewriteAccuracy", "rerouteRate", "dau", "realTimeOnline"]:
    print(f"  {k}: {data.get(k)}")

# 判定
print("\n--- 判定 ---")
l1 = data.get("l1Calls")
print(f"  P0-5/P1-2 (l1Calls>0?): {'✅' if isinstance(l1, (int, float)) and l1 > 0 else '❌ 仍0/None'}")

ra = data.get("rewriteAccuracy")
print(f"  C2 真值 (rewriteAccuracy 非None?): {'✅' if ra is not None else '❌ 仍None (agent.rewrite.accuracy 无 rule_check 计数)'}")

rr = data.get("rerouteRate")
print(f"  C3 (rerouteRate 非None?): {'✅' if rr is not None else '❌ 仍None'}")

rep = get(f"/api/v1/ai/accuracy-report?from={frm}&to={to}")
rdata = rep.get("data", rep)
cm = rdata.get("confusion") if isinstance(rdata, dict) else None
ts = cm.get("totalSamples") if cm else None
print(f"\n=== 2. accuracy-report 混淆矩阵 (I2/P0-6) ===")
print(f"  totalSamples: {ts}  -> {'✅ 有数据' if ts else '❌ 仍0 (agent.intent.accuracy Counter 聚合)'}")
print(f"  OVERALL: {rdata.get('overallStats') if isinstance(rdata, dict) else None}")

print("\n=== 3. 关联端点 ===")
for ep in ["/api/v1/ai/intent-distribution", "/api/v1/ai/tool-stats",
           "/api/v1/ai/token-cost", "/api/v1/ai/satisfaction"]:
    d = get(ep)
    err = d.get("_err") if isinstance(d, dict) else None
    print(f"  {ep}: {'ERR '+str(err) if err else 'OK code='+str(d.get('code'))}")
