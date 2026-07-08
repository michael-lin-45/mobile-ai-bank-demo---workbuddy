import urllib.request, json
url = "http://127.0.0.1:8080/api/bank/chat?sessionId=debug2"
body = json.dumps({"message": "hello"}).encode("utf-8")
req = urllib.request.Request(url, data=body,
    headers={"Content-Type": "application/json"}, method="POST")
try:
    with urllib.request.urlopen(req, timeout=120) as r:
        pass
except urllib.error.HTTPError as e:
    err_body = e.read().decode("utf-8")
    print(json.dumps(json.loads(err_body), indent=2, ensure_ascii=False)[:3000])
