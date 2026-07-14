import urllib.request, json, time, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

def check(url, label):
    try:
        start = time.time()
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=10) as r:
            body = r.read().decode("utf-8")[:150]
            t = (time.time()-start)*1000
            return f"  {label}: OK ({t:.0f}ms) -> {body}"
    except Exception as e:
        return f"  {label}: FAIL -> {e}"

print("=" * 60)
print("全栈验证")
print("=" * 60)

# 1. Backend
print("\n[Backend 9092]")
print(check("http://127.0.0.1:9092/health", "Health"))
print(check("http://127.0.0.1:9092/", "Dashboard"))

# 2. Core
print("\n[Core 8080]")
print(check("http://127.0.0.1:8080/actuator/health", "Health"))

# 3. Chat API
print("\n[聊天 API]")
msgs = ["我想理财投资", "查一下我的余额", "转账100给王五"]
for msg in msgs:
    url = "http://127.0.0.1:8080/api/bank/chat?sessionId=verify"
    body = json.dumps({"message": msg}).encode("utf-8")
    start = time.time()
    try:
        req = urllib.request.Request(url, data=body,
            headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req, timeout=120) as r:
            data = json.loads(r.read().decode("utf-8"))
            t = round(time.time()-start, 1)
            print(f"  [{msg}] -> {data['status']}/{data['intent']} ({t}s)")
    except Exception as e:
        t = round(time.time()-start, 1)
        print(f"  [{msg}] -> ERROR ({t}s): {e}")

# 4. OTel endpoints
print("\n[OTel Endpoints]")
for ep in ["/api/v1/metrics", "/api/v1/traces", "/api/v1/logs"]:
    try:
        req = urllib.request.Request(f"http://127.0.0.1:9092{ep}")
        req.method = "GET"
        with urllib.request.urlopen(req, timeout=5) as r:
            data = r.read().decode("utf-8")
            n = data.count("traceId")
            print(f"  {ep}: OK ({n} traces)")
    except Exception as e:
        print(f"  {ep}: {e}")

print("\n" + "=" * 60)
print("验证完成")
