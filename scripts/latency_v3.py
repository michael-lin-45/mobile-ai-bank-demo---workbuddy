import urllib.request, json, time

url = "http://127.0.0.1:8080/api/bank/chat?sessionId=perftest"
headers = {"Content-Type": "application/json; charset=utf-8"}
messages = [
    "我要买理财",
    "查询余额",
    "转账100元给张三",
    "查账单",
    "买基金",
    "我要投资",
    "理财推荐",
    "查一下收支",
    "转账",
    "理财产品推荐",
]

print(f"{'#':>3} | {'Msg':>14} | {'Latency':>8} | {'Status':>14} | Result")
print("-" * 85)
results = []

for i, msg in enumerate(messages[:10], 1):
    body = json.dumps({"message": msg}, ensure_ascii=False).encode("utf-8")
    start = time.time()
    try:
        req = urllib.request.Request(url, data=body, headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=180) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            elapsed = time.time() - start
            st = data.get("status","?")
            it = data.get("intent","?")
            ct = (data.get("content") or "")[:30].replace("\n"," ").replace("\r","")
            results.append((i, round(elapsed,1), st, it, len(data.get("content","") or "")))
            print(f"{i:>3} | {msg:>14} | {elapsed:>7.1f}s | {st:>14} | {it}")
    except urllib.error.HTTPError as e:
        elapsed = time.time() - start
        err_body = e.read().decode("utf-8")[:200]
        results.append((i, round(elapsed,1), f"HTTP{e.code}", "-", 0))
        print(f"{i:>3} | {msg:>14} | {elapsed:>7.1f}s | HTTP {e.code:>10} | {err_body}")
    except Exception as e:
        elapsed = time.time() - start
        results.append((i, round(elapsed,1), "ERROR", "-", 0))
        print(f"{i:>3} | {msg:>14} | {elapsed:>7.1f}s | {'ERROR':>14} | {str(e)[:50]}")

print("\n" + "=" * 60)
lats = [r[1] for r in results]
errs = sum(1 for r in results if "HTTP" in str(r[2]) or r[2] == "ERROR")
ok = sum(1 for r in results if r[2] not in ("ERROR",) and "HTTP" not in str(r[2]))
print(f"Success: {ok}/10  Errors: {errs}")
if lats:
    print(f"Avg: {sum(lats)/len(lats):.1f}s  Min: {min(lats):.1f}s  Max: {max(lats):.1f}s")
