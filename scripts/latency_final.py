import urllib.request, json, time, datetime

url = "http://127.0.0.1:8080/api/bank/chat"
headers = {"Content-Type": "application/json; charset=utf-8", "Accept": "application/json"}

rounds = [
    (1, "我要买理财"),
    (2, "查询余额"),
    (3, "转账100元给张三"),
    (4, "最近的账单"),
    (5, "推荐一只基金"),
    (6, "我要投资"),
    (7, "理财产品解读"),
    (8, "查一下收支"),
    (9, "你好"),
    (10, "谢谢"),
]

print(f"Start: {datetime.datetime.now().strftime('%H:%M:%S')}")
print(f"{'#':>3} | {'Message':>16} | {'Time':>8} | {'Status':>12} | {'Intent':>15} | Response preview")
print("-" * 95)

results = []
for idx, msg in rounds:
    body = json.dumps({"message": msg}, ensure_ascii=False).encode("utf-8")
    req_url = f"{url}?sessionId=round{idx}"
    start = time.time()
    try:
        req = urllib.request.Request(req_url, data=body, headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=180) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            t = round(time.time() - start, 1)
            st = data.get("status", "?")
            it = data.get("intent", "?")
            ct = (data.get("content", "") or "")[:35].replace("\n", " ").replace("\r", "")
            results.append((idx, t, st, it))
            print(f"{idx:>3} | {msg:>16} | {t:>7.1f}s | {st:>12} | {it:>15} | {ct}")
    except Exception as e:
        t = round(time.time() - start, 1)
        results.append((idx, t, "ERROR", "-"))
        print(f"{idx:>3} | {msg:>16} | {t:>7.1f}s | {'ERROR':>12} | {'-':>15} | {str(e)[:40]}")

print("\n" + "=" * 60)
print(f"End: {datetime.datetime.now().strftime('%H:%M:%S')}")
lats = [r[1] for r in results]
errs = sum(1 for r in results if r[2] == "ERROR")
completes = sum(1 for r in results if r[2] == "COMPLETED")
interrupts = sum(1 for r in results if r[2] == "INTERRUPTED")
print(f"COMPLETED: {completes}  INTERRUPTED: {interrupts}  ERROR: {errs}")
if lats:
    print(f"Avg: {sum(lats)/len(lats):.1f}s  Min: {min(lats):.1f}s  Max: {max(lats):.1f}s")
    print(f"Per intent:")
    from collections import Counter
    intents = Counter(r[3] for r in results if r[2] != "ERROR")
    for intent, count in intents.most_common():
        i_lats = [r[1] for r in results if r[3] == intent]
        print(f"  {intent}: {count} calls, avg {sum(i_lats)/len(i_lats):.1f}s")
