#!/usr/bin/env python3
"""种子数据下发脚本 — Python 版（避 Git Bash 中文编码坑）"""
import time, json
from urllib import request

BASE = "http://127.0.0.1:8080/api/bank/chat"

def send(sid, msg):
    body = json.dumps({"message": msg}, ensure_ascii=False).encode("utf-8")
    url = f"{BASE}?sessionId={sid}"
    req = request.Request(url, data=body, headers={"Content-Type": "application/json"}, method="POST")
    try:
        resp = request.urlopen(req, timeout=120)
        d = json.loads(resp.read().decode())
        print(f"  [{d.get('status','?')}] {d.get('intent','?')} - {d.get('content','')[:50]}")
    except Exception as e:
        print(f"  ERROR: {e}")

print("=" * 50)
print("  Mobile AI Bank — Seed Runner")
print("=" * 50)

# ==================== np1: 基金推荐 (3 turns) ====================
print("\n>>> np1: 基金推荐 (3 turns)")
send("np1", "你好，我想了解一下基金产品，有什么推荐的吗")
time.sleep(4)
send("np1", "能详细说说货币基金和债券基金的区别吗")
time.sleep(4)
send("np1", "那我先买5000块的货币基金试试")
time.sleep(5)

# ==================== np2: 转账金额修正 (4 turns) ====================
print("\n>>> np2: 转账金额修正 (4 turns)")
send("np2", "转账500到张三的工商银行账户")
time.sleep(3)
send("np2", "等等，不是500，改成转1000")
time.sleep(3)
send("np2", "对，就是1000，确认转账")
time.sleep(3)
send("np2", "好的，帮我查一下转账进度")
time.sleep(5)

# ==================== np3: 账单查询 (3 turns) ====================
print("\n>>> np3: 日期范围账单查询 (3 turns)")
send("np3", "帮我查一下这个月的账单")
time.sleep(4)
send("np3", "只看3月1号到3月15号的消费记录")
time.sleep(4)
send("np3", "其中餐饮类的花了多少钱")
time.sleep(5)

# ==================== np4: 理财消歧 (3 turns) ====================
print("\n>>> np4: 理财消歧 (3 turns)")
send("np4", "我想了解一下理财")
time.sleep(4)
send("np4", "帮我分析一下我现在的资产配置")
time.sleep(4)
send("np4", "那推荐一些稳健型的理财产品吧")
time.sleep(5)

# ==================== np5: 闲聊→财富 (4 turns) ====================
print("\n>>> np5: 闲聊→财富请求 (4 turns)")
send("np5", "嗨，早上好")
time.sleep(3)
send("np5", "今天心情不错，想看看有什么好的理财产品")
time.sleep(4)
send("np5", "我风险承受能力一般，推荐什么类型的基金")
time.sleep(4)
send("np5", "谢谢，那就先关注一下混合型基金")
time.sleep(3)

print("\nDone! 5 sessions, 17 turns")
