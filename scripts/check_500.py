import urllib.request, json

url = "http://127.0.0.1:8080/api/bank/chat?sessionId=perftest"
body = json.dumps({"message": "test"}, ensure_ascii=False).encode("utf-8")
req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json; charset=utf-8"}, method="POST")

try:
    with urllib.request.urlopen(req, timeout=120) as r:
        print("Status:", r.status)
        print("Body:", r.read().decode("utf-8")[:500])
except urllib.error.HTTPError as e:
    print("Status:", e.code)
    print("Body:", e.read().decode("utf-8")[:2000])
