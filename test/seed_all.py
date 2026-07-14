#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
统一播种脚本（UTF-8 安全），覆盖原 test/seed.sh(已汉化) + test/seed_new.sh(中文) 全部 36 条对话。
解决 Git Bash 下 curl -d 传中文 UTF-8 损坏导致 Core 400 的问题。
直接 urllib + utf-8 body，确保中文消息正确送达 Core。
"""
import json
import random
import time
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8080/api/bank/chat"

# (sessionId, message, sleep_after_seconds)
TURNS = [
    # ---- seed.sh (已汉化) ----
    ("zs1", "转账50000给李四", 3),
    ("zs1", "确认", 3),
    ("zs1", "好的，继续", 3),
    ("zs2", "查一下上个月的账单", 3),
    ("zs2", "餐饮花了多少钱", 3),
    ("ww1", "推荐稳健的基金", 4),
    ("ww1", "解释一下朝朝盈", 4),
    ("ww1", "还有别的吗", 4),
    ("ww1", "转账3万给赵六", 4),
    ("ww1", "确认", 4),
    ("xm1", "我想看看理财", 4),
    ("xm1", "产品解读", 4),
    ("ll1", "转账500给张三", 3),
    ("ll1", "取消", 3),
    ("am1", "推荐科技板块的基金", 4),
    ("am1", "历史收益", 4),
    ("am1", "风险等级", 4),
    ("xmi1", "你好", 3),
    ("xmi1", "我的工资到账了吗", 3),
    # ---- seed_new.sh (中文) ----
    ("np1", "你好，我想了解一下基金产品，有什么推荐的吗", 4),
    ("np1", "能详细说说货币基金和债券基金的区别吗", 4),
    ("np1", "那我先买5000块的货币基金试试", 5),
    ("np2", "转账500到张三的工商银行账户", 3),
    ("np2", "等等，不是500，改成转1000", 3),
    ("np2", "对，就是1000，确认转账", 3),
    ("np2", "好的，帮我查一下转账进度", 5),
    ("np3", "帮我查一下这个月的账单", 4),
    ("np3", "只看3月1号到3月15号的消费记录", 4),
    ("np3", "其中餐饮类的花了多少钱", 5),
    ("np4", "我想了解一下理财", 4),
    ("np4", "帮我分析一下我现在的资产配置", 4),
    ("np4", "那推荐一些稳健型的理财产品吧", 5),
    ("np5", "嗨，早上好", 3),
    ("np5", "今天心情不错，想看看有什么好的理财产品", 4),
    ("np5", "我风险承受能力一般，推荐什么类型的基金", 4),
    ("np5", "谢谢，那就先关注一下混合型基金", 3),
]

# 每轮播种使用随机 run 前缀，避免重播时 session_turns 叠加旧会话（fix #3 污染）
RUN_PREFIX = "seed-%d-%04x" % (int(time.time()), random.randint(0, 0xFFFF))


def send(session_id, message):
    url = "%s?sessionId=%s" % (BASE, session_id)
    body = json.dumps({"message": message}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=45) as resp:
            raw = resp.read().decode("utf-8", "replace")
        d = json.loads(raw)
        status = d.get("status", "?")
        intent = d.get("intent", "?")
        content = (d.get("content") or "")[:38]
        return "[%s] %s - %s" % (status, intent, content)
    except urllib.error.HTTPError as e:
        return "[HTTP %s] %s" % (e.code, e.read().decode("utf-8", "replace")[:60])
    except Exception as e:
        return "[ERR] %s" % str(e)[:60]

def main():
    ok = 0
    total = len(TURNS)
    for i, (sid, msg, slp) in enumerate(TURNS, 1):
        full_sid = "%s-%s" % (RUN_PREFIX, sid)
        short = (msg[:24] + "..") if len(msg) > 26 else msg
        result = send(full_sid, msg)
        print("[%02d/%02d] %-22s %-26s -> %s" % (i, total, full_sid, short, result), flush=True)
        if result.startswith("[COMPLETED]") or result.startswith("[HTTP"):
            ok += 1
        time.sleep(slp)
    print("\nDONE: %d/%d turns sent (含HTTP错误亦计为已送达)" % (ok, total))

if __name__ == "__main__":
    main()
