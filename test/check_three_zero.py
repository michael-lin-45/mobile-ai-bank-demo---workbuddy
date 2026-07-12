import urllib.request, json, sys

PY = "C:/Users/Administrator/.workbuddy/binaries/python/versions/3.13.12/python.exe"
B = "http://127.0.0.1:9090"
C = "http://127.0.0.1:8080"

def get(url):
    try:
        req = urllib.request.Request(url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=8) as r:
            return json.loads(r.read().decode())
    except Exception as e:
        return {"_error": str(e)}

def show(label, d, keys=None):
    print(f"\n===== {label} =====")
    if isinstance(d, dict) and "_error" in d:
        print("  ERROR:", d["_error"]); return
    if keys:
        for k in keys:
            print(f"  {k} = {d.get(k)}" if isinstance(d, dict) else d)
    else:
        s = json.dumps(d, ensure_ascii=False)
        print(s[:2000])

# 1. 服务健康
show("BACKEND /actuator/health", get(B+"/actuator/health"))
show("CORE /actuator/health", get(C+"/actuator/health"))

# 2. 总览大屏 realtime (Zone B: Token + TTFT)
rt = get(B+"/api/v1/metrics/realtime")
if isinstance(rt, dict):
    data = rt.get("data", rt)
    show("REALTIME (token/ttft 关键字段)", data,
         ["tokenCallCount","tokenInput","tokenOutput","ttftP50","ttftP95","ttftP99","fallbackMetrics"])
else:
    show("REALTIME raw", rt)

# 3. AGENT 性能
show("AGENT-PERF (dimension=agent)", get(B+"/api/v1/ai/agent-performance?dimension=agent"))

# 4. TOKEN 成本 trend + detail
show("TOKEN-COST trend", get(B+"/api/v1/ai/token-cost?type=trend"))
show("TOKEN-COST detail", get(B+"/api/v1/ai/token-cost?type=detail"))
