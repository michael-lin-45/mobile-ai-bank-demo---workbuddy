import json, sys
from urllib import request

url = "http://127.0.0.1:3001/api/v1/metrics/history?from=2026-06-29T00:00:00Z&to=2026-06-29T10:00:00Z&step=1m"
resp = request.urlopen(url, timeout=30)
data = json.loads(resp.read().decode())['data']

token_metrics = [m for m in data if 'token' in m.get('metricName','') or 'llm' in m.get('metricName','')]
seen = set()
for m in token_metrics:
    key = m['metricName']
    if key not in seen:
        seen.add(key)
        print(f"{key}: value={m['value']}")
print(f"\nTotal token/llm metrics: {len(token_metrics)}")

# Also check for specific metrics
for name in ['llm.token.input', 'llm.token.output', 'llm.first_token.latency', 'llm.operation.duration']:
    found = [m for m in data if m['metricName'] == name and m['value'] > 0]
    print(f"{name}: found {len(found)} with value>0")
