#!/usr/bin/env python3
"""Simulate SessionBridge POST to observability backend"""
import json
from urllib import request

body = json.dumps({
    "sessionId": "sim-bridge-001",
    "userId": "sim",
    "userInput": "transfer 100 to Alice",
    "aiResponse": "转账成功！已向Alice转账100元。交易流水号：TXN203005",
    "intent": "TRANSFER",
    "agentPath": "TRANSFER",
    "confidence": 0.0,
    "durationMs": 0,
    "tokens": 0,
    "traceId": "",
    "status": "COMPLETED"
}, ensure_ascii=False).encode("utf-8")

req = request.Request(
    "http://127.0.0.1:9090/api/v1/sessions",
    data=body,
    headers={"Content-Type": "application/json; charset=utf-8"},
    method="POST"
)

try:
    resp = request.urlopen(req, timeout=10)
    print(f"Status: {resp.status}")
    print(resp.read().decode())
except Exception as e:
    print(f"Error: {e}")
