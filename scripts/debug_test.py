import urllib.request, json, time, traceback

url = "http://127.0.0.1:8080/api/bank/chat?sessionId=t1"
headers = {"Content-Type": "application/json; charset=utf-8", "Accept": "application/json"}
body = json.dumps({"message": "test"}, ensure_ascii=True).encode("utf-8")

try:
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=30) as resp:
        print("HTTP:", resp.status)
        print("Body:", resp.read().decode("utf-8")[:1000])
except urllib.error.HTTPError as e:
    print("HTTPError:", e.code)
    print("Body:", e.read().decode("utf-8", errors="replace")[:1000])
except Exception as e:
    print("Exception type:", type(e).__name__)
    print("Exception:", e)
    traceback.print_exc()
