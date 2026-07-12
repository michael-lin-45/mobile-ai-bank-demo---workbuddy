"""Send a few L2 (non-chat) requests to Core to populate TTFT + token metrics.
Run AFTER the backend has been restarted with the new jar (so TTFT Histogram is parsed).
"""
import urllib.request, json, time

CORE = "http://127.0.0.1:8080/api/bank/chat"

TURNS = [
    ("seedA", "transfer 50000 to Li Si"),
    ("seedA", "yes confirm"),
    ("seedB", "show last month bills"),
    ("seedC", "recommend conservative funds"),
    ("seedC", "explain Chaochao Ying"),
    ("seedD", "I want to check wealth"),
    ("seedE", "recommend tech sector funds"),
    ("seedF", "hello there"),  # chat (still emits tokens + TTFT)
]

def send(sid, msg):
    body = json.dumps({"message": msg}).encode()
    req = urllib.request.Request(CORE + "?sessionId=" + sid, data=body,
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            d = json.loads(r.read().decode())
            print(f"  [{d.get('status')}] intent={d.get('intent')} - {(d.get('content') or '')[:50]}")
    except Exception as e:
        print(f"  ERR {sid}/{msg[:20]}: {e}")

if __name__ == "__main__":
    print("Sending L2 traffic to Core ...")
    for sid, msg in TURNS:
        send(sid, msg)
        time.sleep(2)
    print("Done. Waiting 60s for OTel export + 30s for Redis->H2 TTFT snapshot ...")
