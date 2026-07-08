import urllib.request
import json
import time

url = "http://127.0.0.1:8080/api/bank/chat?sessionId=perftest"
body = json.dumps({"message": "我要买理财"}, ensure_ascii=False).encode("utf-8")
headers = {"Content-Type": "application/json; charset=utf-8"}

results = []
print(f"{'#':>3} | {'Latency(s)':>10} | {'Status':>12} | {'Intent':>16} | Content")
print("-" * 85)

for i in range(1, 11):
    start = time.time()
    try:
        req = urllib.request.Request(url, data=body, headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            elapsed = time.time() - start
            status = data.get("status", "?")
            intent = data.get("intent", "?")
            ct = data.get("content", "")
            clen = len(ct or "")
            results.append({"round": i, "latency": round(elapsed, 1), "status": status, "intent": intent, "len": clen})
            preview = (ct or "")[:40].replace("\n"," ")
            print(f"{i:>3} | {elapsed:>9.1f}s | {status:>12} | {intent:>16} | {clen:>3} | {preview}")
    except Exception as e:
        elapsed = time.time() - start
        results.append({"round": i, "latency": round(elapsed, 1), "status": "ERROR", "intent": "-", "len": 0})
        print(f"{i:>3} | {elapsed:>9.1f}s | {'ERROR':>12} | {'-':>16} |   0 | {str(e)[:50]}")

print("\n" + "=" * 50)
lats = [r["latency"] for r in results]
errs = sum(1 for r in results if r["status"] == "ERROR")
if lats:
    print(f"Rounds: {len(results)}  Errors: {errs}")
    print(f"Average: {sum(lats)/len(lats):.1f}s")
    print(f"Min:     {min(lats):.1f}s")
    print(f"Max:     {max(lats):.1f}s")
