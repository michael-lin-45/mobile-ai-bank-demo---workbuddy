import json, urllib.request, sys

BASE = "http://127.0.0.1:9090"

# 1) get first trace id
with urllib.request.urlopen(BASE + "/api/v1/traces", timeout=10) as r:
    lst = json.load(r)
content = lst.get("data", {}).get("content", [])
if not content:
    print("NO TRACES"); sys.exit()
tid = content[0]["traceId"]
print("traceId=", tid)

# 2) get detail
with urllib.request.urlopen(BASE + "/api/v1/traces/" + tid, timeout=10) as r:
    vo = json.load(r).get("data", {})

print("detail keys=", list(vo.keys()))

def walk(nodes, depth=0):
    for n in (nodes or []):
        op = n.get("operationName") or n.get("name")
        layer = n.get("agentLayer") or n.get("agent_layer")
        intent = n.get("intent")
        aname = n.get("agentName") or n.get("agent_name")
        attrs = n.get("attributes")
        # collect any attr key containing intent/domain/agent
        extra = {}
        if isinstance(attrs, dict):
            for k, v in attrs.items():
                if "intent" in k.lower() or "domain" in k.lower() or "agent" in k.lower():
                    extra[k] = v
        print(f"  [{depth}] op={op} | layer={layer} | intent={intent} | agentName={aname} | extra={extra}")
        ch = n.get("children")
        if ch:
            walk(ch, depth + 1)

if "spans" in vo:
    walk(vo["spans"])
elif "spanTree" in vo:
    walk([vo["spanTree"]])
elif "tree" in vo:
    walk([vo["tree"]])
else:
    print("unknown shape:", str(vo)[:800])
