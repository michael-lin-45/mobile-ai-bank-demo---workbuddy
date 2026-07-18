import subprocess, time, shutil, sys
from pathlib import Path
from playwright.sync_api import sync_playwright

OUT = Path(r"D:/GitHub/mobile-ai-bank-demo - workbuddy/ppt/AI可观测设计方案/assets")
OUT.mkdir(parents=True, exist_ok=True)
EDGE = r"C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
TMP = Path(r"D:/GitHub/mobile-ai-bank-demo - workbuddy/ppt/.edge_profile")
TMP.mkdir(parents=True, exist_ok=True)

proc = subprocess.Popen(
    [EDGE, "--headless=new", "--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage",
     "--remote-debugging-port=9222", f"--user-data-dir={TMP}", "about:blank"],
    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

try:
    # wait for devtools port
    for _ in range(40):
        if shutil.which("curl"):
            r = subprocess.run(["curl","-s","--max-time","2","http://127.0.0.1:9222/json/version"],
                               capture_output=True, text=True)
            if r.returncode == 0 and "webSocketDebuggerUrl" in r.stdout:
                break
        time.sleep(0.5)
    else:
        print("Edge devtools not up"); sys.exit(1)

    with sync_playwright() as p:
        browser = p.chromium.connect_over_cdp("http://127.0.0.1:9222")
        ctx = browser.new_context(viewport={"width":1440,"height":900}, device_scale_factor=2)
        page = ctx.new_page()
        targets = [
            (r"D:/GitHub/mobile-ai-bank-demo - workbuddy/observability/frontend/可观测DEMO-v20-WorkBuddy.html",
             [("总览大屏","v20_overview"),("会话回放","v20_session"),("链路追踪","v20_trace"),
              ("AI 洞察","v20_insight"),("智能洞察","v20_intelligence"),("日志查询","v20_logs"),("告警规则","v20_alerts")]),
            (r"D:/GitHub/mobile-ai-bank-demo - workbuddy/observability/frontend/可观测DEMO-v16.html",
             [("总览大屏","v16_overview")]),
        ]
        for f, views in targets:
            page.goto(Path(f).as_uri(), wait_until="networkidle", timeout=60000)
            time.sleep(2.5)
            for label, name in views:
                try:
                    page.click(f"text={label}", timeout=8000)
                except Exception as e:
                    print(f"  click fail {label}: {e}")
                time.sleep(3.0)
                page.screenshot(path=str(OUT / f"{name}.png"))
                print(f"shot {name}.png")
        ctx.close()
        browser.close()
    print("DONE")
finally:
    proc.terminate()
    try: proc.wait(timeout=5)
    except Exception: proc.kill()
