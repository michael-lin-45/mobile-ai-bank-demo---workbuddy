import json, urllib.request

BASE = "http://127.0.0.1:9090"

def get(path):
    req = urllib.request.Request(BASE + path, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode())

def wrap(obj):
    # API wraps as {code,message,data}
    return obj.get("data", obj) if isinstance(obj, dict) else obj

print("=== /api/v1/traces ===")
traces = wrap(get("/api/v1/traces?size=200"))
content = traces.get("content", []) if isinstance(traces, dict) else traces
print("totalElements=", (traces.get("totalElements") if isinstance(traces, dict) else len(content)), "returned=", len(content))

old_fmt = [t for t in content if (t.get("agentChain") or "").find("L1-LLM1") >= 0]
print("OLD-format agentChain (L1-LLM1 present):", len(old_fmt))
print("\n-- sample traces (first 12) --")
for t in content[:12]:
    print("  %s | chain=%r | intent=%r | status=%s" % (
        str(t.get("traceId"))[:10], t.get("agentChain"), t.get("intent"), t.get("statusCode")))

wealth = [t for t in content if "WEALTH" in (t.get("agentChain") or "") or "WEALTH" in (t.get("intent") or "")]
print("\n-- WEALTH-domain traces (show L0->L1->WEALTH style) --")
for t in wealth[:6]:
    print("  %s | chain=%r | intent=%r" % (str(t.get("traceId"))[:10], t.get("agentChain"), t.get("intent")))

print("\n=== /api/v1/sessions ===")
sess = wrap(get("/api/v1/sessions?size=200"))
scontent = sess.get("content", []) if isinstance(sess, dict) else sess
print("totalElements=", (sess.get("totalElements") if isinstance(sess, dict) else len(scontent)), "returned=", len(scontent))
print("\n-- all sessions: agents (per-turn final L2 / L1) + intentFlow --")
for s in scontent:
    print("  %s | turns=%s | intent=%r | domainSwitches=%s | agents=%r" % (
        str(s.get("sessionId"))[:14], s.get("turnCount"), s.get("intent"),
        s.get("domainSwitches"), s.get("agents")))
