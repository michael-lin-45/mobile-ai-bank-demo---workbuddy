"""Send a few L2 (non-chat) requests to Core to populate TTFT + token metrics.
Run AFTER the backend has been restarted with the new jar (so TTFT Histogram is parsed).
"""
import urllib.request, json, time

CORE = "http://127.0.0.1:8080/api/bank/chat"

TURNS = [
    ("seedA", "转账50000给李四"),
    ("seedA", "确认"),
    ("seedB", "查一下上个月的账单"),
    ("seedC", "推荐稳健的基金"),
    ("seedC", "解释一下朝朝盈"),
    ("seedD", "我想看看理财"),
    ("seedE", "推荐科技板块的基金"),
    ("seedF", "你好"),  # 聊天（仍产生 token + TTFT）
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
    print("正在向 Core 发送 L2 流量 ...")
    for sid, msg in TURNS:
        send(sid, msg)
        time.sleep(2)
    print("完成。等待 60s OTel 上报 + 30s Redis->H2 的 TTFT 快照 ...")
