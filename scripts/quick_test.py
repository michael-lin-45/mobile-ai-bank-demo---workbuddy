import urllib.request, json, time

url = "http://127.0.0.1:8080/api/bank/chat?sessionId=t1"
headers = {"Content-Type": "application/json; charset=utf-8", "Accept": "application/json"}
msgs = ["I want to buy finance","check balance","transfer 100 to Tom","bill query","buy fund","invest","finance recommend"]

print(f"{'#':>3} | {'Msg':>22} | {'Time':>7} | Status")
print("-" * 55)
for i, msg in enumerate(msgs, 1):
    body = json.dumps({"message": msg}, ensure_ascii=True).encode("utf-8")
    start = time.time()
    try:
        req = urllib.request.Request(url, data=body, headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=180) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            t = round(time.time()-start, 1)
            print(f"{i:>3} | {msg:>22} | {t:>6.1f}s | {data.get('status','?')} {data.get('intent','?')}")
    except urllib.error.HTTPError as e:
        t = round(time.time()-start, 1)
        body = e.read().decode("utf-8", errors="replace")[:500]
        print(f"{i:>3} | {msg:>22} | {t:>6.1f}s | HTTP{e.code}: {body}")
    except Exception as e:
        t = round(time.time()-start, 1)
        print(f"{i:>3} | {msg:>22} | {t:>6.1f}s | ERROR: {e}")
