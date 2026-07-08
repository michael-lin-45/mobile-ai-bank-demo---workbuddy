import urllib.request
import json
import time
import sys

url = "http://127.0.0.1:8080/api/bank/chat?sessionId=perftest"
body = json.dumps({"message": "查询余额"}, ensure_ascii=False).encode("utf-8")
headers = {"Content-Type": "application/json; charset=utf-8"}

results = []
print(f"{'Round':>5} | {'Latency(s)':>10} | {'Status':>15} | {'Intent':>15} | ContentLen")
print("-" * 75)

for i in range(1, 11):
    start = time.time()
    try:
        req = urllib.request.Request(url, data=body, headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            elapsed = time.time() - start
            status = data.get("status", "?")
            intent = data.get("intent", "?")
            ct = len(data.get("content", "") or "")
            results.append({"round": i, "latency": round(elapsed, 1), "status": status, "intent": intent, "contentLen": ct})
            print(f"{i:>5} | {elapsed:>9.1f}s | {status:>15} | {intent:>15} | {ct}")
    except Exception as e:
        elapsed = time.time() - start
        results.append({"round": i, "latency": round(elapsed, 1), "status": "ERROR", "intent": "-", "contentLen": 0})
        print(f"{i:>5} | {elapsed:>9.1f}s | {'ERROR':>15} | {'-':>15} | {str(e)[:50]}")

print("\n========== SUMMARY ==========")
lats = [r["latency"] for r in results]
avg = sum(lats) / len(lats)
min_l = min(lats)
max_l = max(lats)
print(f"Total rounds: {len(results)}")
print(f"Average: {avg:.1f}s")
print(f"Min:     {min_l:.1f}s")
print(f"Max:     {max_l:.1f}s")
print(f"Errors:  {sum(1 for r in results if r['status'] == 'ERROR')}")
