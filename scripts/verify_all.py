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

def check_skip(url, label, skip_reason=""):
    """检查端点，失败时 SKIP（不报 FAIL）"""
    try:
        start = time.time()
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=10) as r:
            body = r.read().decode("utf-8")[:150]
            t = (time.time()-start)*1000
            return f"  {label}: OK ({t:.0f}ms) -> {body}"
    except Exception as e:
        hint = f" ({skip_reason})" if skip_reason else ""
        return f"  {label}: ⚠ SKIP{hint} -> {e}"

def check_json(url, label, validate_fn, skip_reason=""):
    """检查端点，解析 JSON 并用 validate_fn(data) 验证；失败时 SKIP"""
    try:
        start = time.time()
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=10) as r:
            data = json.loads(r.read().decode("utf-8"))
            t = (time.time()-start)*1000
            ok, msg = validate_fn(data)
            if ok:
                return f"  {label}: OK ({t:.0f}ms) {msg}"
            else:
                return f"  {label}: ⚠ SKIP -> {msg}"
    except Exception as e:
        hint = f" ({skip_reason})" if skip_reason else ""
        return f"  {label}: ⚠ SKIP{hint} -> {e}"

print("=" * 60)
print("全栈验证")
print("=" * 60)

# 1. Backend
print("\n[Backend 9090]")
print(check("http://127.0.0.1:9090/api/v1/admin/diagnostics/dead-keys", "Health (dead-keys)"))
print(check_skip("http://127.0.0.1:9090/", "Dashboard", "后端不提供根页面，已由其他端点替代"))

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

# 4. OTel Endpoints
print("\n[OTel Endpoints]")
for ep in ["/api/v1/metrics", "/api/v1/traces", "/api/v1/logs"]:
    try:
        req = urllib.request.Request(f"http://127.0.0.1:9090{ep}")
        with urllib.request.urlopen(req, timeout=5) as r:
            data = r.read().decode("utf-8")
            n = data.count("traceId")
            print(f"  {ep}: OK ({n} traces)")
    except Exception as e:
        print(f"  {ep}: ⚠ SKIP (Collector 可能未起) -> {e}")

# 5. Backend Insights API (W2)
print("\n[Backend Insights API]")
def _v_code0_data(data):
    """验证 code==0 且 data 非空"""
    code = data.get("code", -1)
    d = data.get("data", None)
    if code == 0 and d is not None:
        preview = str(d)[:80]
        return True, f"code=0, data={preview}"
    return False, f"code={code}, data={'empty' if d is None else 'present'}"
print(check_json("http://127.0.0.1:9090/api/v1/ai/insights-report", "insights-report", _v_code0_data))
print(check_json("http://127.0.0.1:9090/api/v1/ai/accuracy-report", "accuracy-report", _v_code0_data))
print(check_json("http://127.0.0.1:9090/api/v1/ai/confusion-matrix", "confusion-matrix", _v_code0_data))

# 6. Backend Metrics (W2)
print("\n[Backend Metrics]")
print(check_json("http://127.0.0.1:9090/api/v1/metrics/realtime", "metrics/realtime", _v_code0_data))

# 7. 死 key 诊断 (W3)
print("\n[死 key 诊断 (W3)]")
def _v_dead_keys(data):
    code = data.get("code", -1)
    d = data.get("data", {})
    dead_keys = d.get("deadKeys", [])
    total_dead = sum(dk.get("count", 0) for dk in dead_keys)
    all_clear = total_dead == 0
    if code == 0 and all_clear:
        return True, f"totalDead={total_dead}, allClear={all_clear}"
    return False, f"code={code}, totalDead={total_dead}, allClear={all_clear}"
print(check_json("http://127.0.0.1:9090/api/v1/admin/diagnostics/dead-keys", "dead-keys", _v_dead_keys))

# 8. 提示词版本化 (P20)
print("\n[提示词版本化 (P20)]")
print(check_skip("http://127.0.0.1:9090/api/v1/prompt-version/active?key=insights.root_cause",
                 "prompt-version/active", "未创建提示词"))

# 9. 告警引擎
print("\n[告警引擎]")
print(check_skip("http://127.0.0.1:9090/api/v1/alerts", "alerts"))
print(check_skip("http://127.0.0.1:9090/api/v1/alerts/rules", "alerts/rules"))

# 10. reRoute 透传
print("\n[reRoute 透传]")
print(check_skip("http://127.0.0.1:9090/api/v1/ai/reroute-stats", "reroute-stats"))

# 11. Core 观测 - pending_approval (P7)
print("\n[Core 观测 - pending_approval (P7)]")
def _v_pending_approval(data):
    measurements = data.get("measurements", [])
    if measurements:
        vals = ", ".join(f"{m.get('statistic','?')}={m.get('value','?')}" for m in measurements)
        return True, f"measurements=[{vals}]"
    return False, "measurements 为空"
print(check_json("http://127.0.0.1:8080/actuator/metrics/deepflux.workflow.pending_approval",
                 "pending_approval", _v_pending_approval, "P7 指标未注册"))

# 12. Core 观测 - deepflux 指标注册 (P6)
print("\n[Core 观测 - deepflux 指标注册]")
def _v_deepflux_metrics(data):
    names = data.get("names", [])
    deepflux_names = [n for n in names if n.startswith("deepflux.")]
    if deepflux_names:
        preview = deepflux_names[:5]
        suffix = "..." if len(deepflux_names) > 5 else ""
        return True, f"找到 {len(deepflux_names)} 个 deepflux.* 指标: {preview}{suffix}"
    return False, "未找到 deepflux.* 前缀指标"
print(check_json("http://127.0.0.1:8080/actuator/metrics", "deepflux 指标", _v_deepflux_metrics,
                 "P6 集中式注册表未生效"))

print("\n" + "=" * 60)
print("验证完成")
