import json, urllib.request, re

BASE = "http://127.0.0.1:9090"

def get(path):
    req = urllib.request.Request(BASE + path, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode())

def wrap(obj):
    return obj.get("data", obj) if isinstance(obj, dict) else obj

# L1 层路由识别结果的关键词（来自 Core commitIntent 写入的 span.intent）
L1_KEYWORDS = re.compile(r"(SWITCH|FOLLOW|RESUME|switch|follow|resume|-filter|filter|compare|query|consult|recommend|reroute)", re.I)

print("=== /api/v1/traces ===")
traces = wrap(get("/api/v1/traces?size=300"))
content = traces.get("content", []) if isinstance(traces, dict) else traces
total = traces.get("totalElements") if isinstance(traces, dict) else len(content)
print("totalElements =", total, " returned =", len(content))

old_fmt = [t for t in content if (t.get("agentChain") or "").find("L1-LLM1") >= 0]
print("#1 OLD-format agentChain (L1-LLM1 present):", len(old_fmt))

with_l1 = [t for t in content if L1_KEYWORDS.search(t.get("intent") or "")]
print("#2 intent chains containing L1 routing keywords:", len(with_l1), "/", len(content))

print("\n-- ALL traces: chain + intent --")
for t in content:
    chain = t.get("agentChain")
    intent = t.get("intent")
    flag = "  <<L1!!" if L1_KEYWORDS.search(intent or "") else ""
    print("  [%s] chain=%r | intent=%r%s" % (str(t.get("traceId"))[:10], chain, intent, flag))

print("\n-- Sample of #2 L1-bearing intent chains --")
for t in with_l1[:10]:
    print("  chain=%r" % t.get("agentChain"))
    print("    intent=%r" % t.get("intent"))
