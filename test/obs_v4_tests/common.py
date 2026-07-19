#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
common.py — 可观测 V4 测试公共模块（obs_v4_tests）

提供：
  - 配置读取（BACKEND_URL / CORE_URL / REQUEST_TIMEOUT / VERBOSE / SEED_IF_EMPTY / REPO_ROOT）
  - ApiClient：基于 requests，统一 ApiResponse{code,message,data} 解包
  - TestResult / TestRunner：结果收集与汇总（支持 PASS/FAIL/SKIP）
  - 常用断言助手（code 判定、字段存在、boundary 字段口径等）
  - 可选自播种（ensure_seeded）：后端空时跑 seed_all.py 造数据

约定：
  - 后端默认 http://127.0.0.1:9090，前缀 /api/v1；BACKEND_URL 可覆盖。
  - code == 0 视为业务成功（与前端 client.js 一致）。
  - 所有断言基于 code / data，杜绝「接口存在即通过」。
"""
import os
import time
import json
import subprocess

import requests

# ----------------------------- 配置区 ----------------------------------------
BACKEND_URL = os.environ.get("BACKEND_URL", "http://127.0.0.1:9090").rstrip("/")
CORE_URL = os.environ.get("CORE_URL", "http://127.0.0.1:8080").rstrip("/")
REQUEST_TIMEOUT = int(os.environ.get("REQ_TIMEOUT", "30"))
VERBOSE = os.environ.get("VERBOSE", "0") == "1"
SEED_IF_EMPTY = os.environ.get("SEED_IF_EMPTY", "0") == "1"

REPO_ROOT = os.environ.get("REPO_ROOT") or os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..")
)

VALID_BOUNDARY = {"L1", "L2", "L3"}


# ----------------------------- 结果模型 --------------------------------------
class TestResult:
    """单条用例结果。"""

    def __init__(self, case_id, description, task=None):
        self.case_id = case_id
        self.description = description
        self.task = task
        self.status = "SKIP"   # PASS / FAIL / SKIP
        self.error = None
        self.detail = None

    def set_pass(self, detail=None):
        self.status = "PASS"
        self.error = None
        self.detail = detail

    def set_fail(self, error, detail=None):
        self.status = "FAIL"
        self.error = error
        self.detail = detail

    def set_skip(self, reason, detail=None):
        self.status = "SKIP"
        self.error = reason
        self.detail = detail

    @property
    def passed(self):
        return self.status == "PASS"

    @property
    def failed(self):
        return self.status == "FAIL"

    @property
    def skipped(self):
        return self.status == "SKIP"


class TestRunner:
    """收集并汇报一组用例结果。"""

    def __init__(self, name="suite"):
        self.name = name
        self.results = []

    def record(self, res):
        self.results.append(res)
        mark = {"PASS": "✓", "FAIL": "✗", "SKIP": "⤼"}[res.status]
        print("  %s %s: %s" % (mark, res.case_id, res.description), flush=True)
        if res.error:
            print("         └─ %s" % res.error, flush=True)
        return res

    def pass_(self, case_id, desc, task=None, detail=None):
        r = TestResult(case_id, desc, task)
        r.set_pass(detail)
        return self.record(r)

    def fail(self, case_id, desc, err, task=None, detail=None):
        r = TestResult(case_id, desc, task)
        r.set_fail(err, detail)
        return self.record(r)

    def skip(self, case_id, desc, reason, task=None):
        r = TestResult(case_id, desc, task)
        r.set_skip(reason)
        return self.record(r)

    def summary(self):
        total = len(self.results)
        passed = sum(1 for r in self.results if r.passed)
        failed = sum(1 for r in self.results if r.failed)
        skipped = sum(1 for r in self.results if r.skipped)
        print()
        print("=" * 64)
        print("  Suite: %s" % self.name)
        print("  Total: %d  PASS: %d  FAIL: %d  SKIP: %d"
              % (total, passed, failed, skipped))
        print("=" * 64)
        if failed:
            print("\n  Failed cases:")
            for r in self.results:
                if r.failed:
                    print("    [%s] %s" % (r.case_id, r.description))
                    print("      -> %s" % r.error)
        return total, passed, failed, skipped


# ----------------------------- HTTP 客户端 ------------------------------------
class ApiClient:
    """封装 requests，统一解包 ApiResponse。"""

    def __init__(self, base_url=BACKEND_URL):
        self.base = base_url
        self.session = requests.Session()
        self.session.headers.update({
            "Accept": "application/json",
            "User-Agent": "obs-v4-qa/1.0",
        })

    def _req(self, method, path, body=None, params=None):
        url = self.base + path
        headers = {"Content-Type": "application/json"} if body is not None else None
        if VERBOSE:
            print("    [DEBUG] %s %s params=%s" % (method, url, params))
        try:
            resp = self.session.request(
                method, url, json=body, params=params,
                timeout=REQUEST_TIMEOUT, headers=headers,
            )
            status = resp.status_code
            try:
                raw = resp.json()
            except ValueError:
                raw = {"_raw": resp.text}
            biz = raw.get("code") if isinstance(raw, dict) else None
            return raw, status, biz
        except requests.RequestException as e:
            return {"_error": str(e)}, 0, None

    def get(self, path, params=None):
        return self._req("GET", path, params=params)

    def post(self, path, body=None):
        raw, status, biz = self._req("POST", path, body=body)
        return raw, biz, status

    def put(self, path, body=None):
        raw, status, biz = self._req("PUT", path, body=body)
        return raw, biz, status

    def delete(self, path, body=None):
        raw, status, biz = self._req("DELETE", path, body=body)
        return raw, biz, status

    def get_data(self, path, params=None):
        """GET 并解包 data 字段，返回 (data, biz_code, http_status)。"""
        raw, status, biz = self.get(path, params=params)
        if isinstance(raw, dict) and "data" in raw:
            return raw["data"], biz, status
        return raw, biz, status


# ----------------------------- 断言助手 ---------------------------------------
def assert_code_ok(data, biz, status, case_id, desc, runner, task=None):
    """code==0 或 (http==200 且业务码缺失) 视为成功。"""
    ok = (biz == 0) or (status == 200 and biz is None)
    if ok:
        runner.pass_(case_id, desc, task)
    else:
        runner.fail(case_id, desc, "code=%s, http=%s" % (biz, status), task)
    return ok


def assert_field(item, field, case_id, desc, runner, task=None):
    if isinstance(item, dict) and field in item and item[field] is not None:
        runner.pass_(case_id, desc, task)
        return True
    runner.fail(case_id, desc, "missing/None field '%s'" % field, task)
    return False


def assert_nonempty_list(data, case_id, desc, runner, task=None):
    if isinstance(data, list) and len(data) > 0:
        runner.pass_(case_id, desc, task)
        return True
    runner.fail(case_id, desc,
                "expected non-empty list, got %s" % type(data).__name__, task)
    return False


def assert_boundary(item, expected, case_id, desc, runner, task=None):
    """
    断言洞察 VO 的边界字段口径（详细设计 §3.3③）：
      - boundary ∈ {L1,L2,L3}
      - 期望 boundary 匹配
      - L1: confidence 必须为 null
      - L2/L3: confidence ∈ [0,1]
      - requiresApproval 必须为 bool
    """
    if not isinstance(item, dict):
        runner.fail(case_id, desc, "item is not dict: %s" % type(item).__name__, task)
        return False
    b = item.get("boundary")
    if b not in VALID_BOUNDARY:
        runner.fail(case_id, desc, "boundary missing/invalid: %r" % b, task)
        return False
    if b != expected:
        runner.fail(case_id, desc, "expected boundary=%s, got %s" % (expected, b), task)
        return False
    conf = item.get("confidence")
    if b == "L1":
        if conf is not None:
            runner.fail(case_id, desc, "L1 should have confidence=null, got %r" % conf, task)
            return False
    else:
        if not (isinstance(conf, (int, float)) and 0.0 <= float(conf) <= 1.0):
            runner.fail(case_id, desc,
                        "%s confidence must be in [0,1], got %r" % (b, conf), task)
            return False
    ra = item.get("requiresApproval")
    if not isinstance(ra, bool):
        runner.fail(case_id, desc, "requiresApproval must be bool, got %r" % ra, task)
        return False
    runner.pass_(case_id, desc, task)
    return True


def ceil_div(n, d):
    return (n + d - 1) // d if d else 0


# ----------------------------- 数据准备 ---------------------------------------
# 复制自 test/seed_all.py 的对话剧本（用于必要时自播种）
SEED_TURNS = [
    ("zs1", "转账50000给李四", 3), ("zs1", "确认", 3), ("zs1", "好的，继续", 3),
    ("zs2", "查一下上个月的账单", 3), ("zs2", "餐饮花了多少钱", 3),
    ("ww1", "推荐稳健的基金", 4), ("ww1", "解释一下朝朝盈", 4), ("ww1", "还有别的吗", 4),
    ("ww1", "转账3万给赵六", 4), ("ww1", "确认", 4),
    ("xm1", "我想看看理财", 4), ("xm1", "产品解读", 4),
    ("ll1", "转账500给张三", 3), ("ll1", "取消", 3),
    ("am1", "推荐科技板块的基金", 4), ("am1", "历史收益", 4), ("am1", "风险等级", 4),
    ("xmi1", "你好", 3), ("xmi1", "我的工资到账了吗", 3),
    ("np1", "你好，我想了解一下基金产品，有什么推荐的吗", 4),
    ("np1", "能详细说说货币基金和债券基金的区别吗", 4),
    ("np1", "那我先买5000块的货币基金试试", 5),
    ("np2", "转账500到张三的工商银行账户", 3), ("np2", "等等，不是500，改成转1000", 3),
    ("np2", "对，就是1000，确认转账", 3), ("np2", "好的，帮我查一下转账进度", 5),
    ("np3", "帮我查一下这个月的账单", 4), ("np3", "只看3月1号到3月15号的消费记录", 4),
    ("np3", "其中餐饮类的花了多少钱", 5),
    ("np4", "我想了解一下理财", 4), ("np4", "帮我分析一下我现在的资产配置", 4),
    ("np4", "那推荐一些稳健型的理财产品吧", 5),
    ("np5", "嗨，早上好", 3), ("np5", "今天心情不错，想看看有什么好的理财产品", 4),
    ("np5", "我风险承受能力一般，推荐什么类型的基金", 4),
    ("np5", "谢谢，那就先关注一下混合型基金", 3),
]


def seed_core_traffic(client=None):
    """向 Core(8080) 发送种子对话，产生 spans/sessions/metrics。"""
    if client is None:
        client = ApiClient(CORE_URL)
    core_chat = CORE_URL + "/api/bank/chat"
    ok = 0
    total = len(SEED_TURNS)
    for i, (sid, msg, slp) in enumerate(SEED_TURNS, 1):
        body = {"message": msg}
        try:
            resp = client.session.request(
                "POST", core_chat,
                params={"sessionId": sid},
                json=body, timeout=REQUEST_TIMEOUT,
            )
            if resp.status_code < 500:
                ok += 1
        except requests.RequestException:
            pass
        if VERBOSE:
            print("    [seed] %d/%d %s" % (i, total, sid))
        time.sleep(slp)
    return ok


def ensure_seeded(backend, min_sessions=1):
    """
    检查后端是否已有会话数据；若 SEED_IF_EMPTY 且为空，则运行 seed_all.py。
    返回 True 表示已有（或可造）数据。
    """
    data, biz, status = backend.get_data("/api/v1/sessions?size=1")
    has = isinstance(data, dict) and isinstance(data.get("content"), list) \
        and len(data["content"]) >= min_sessions
    if has:
        return True
    if not SEED_IF_EMPTY:
        return False
    # 尝试运行仓库自带 seed_all.py
    seed_script = os.path.join(REPO_ROOT, "test", "seed_all.py")
    if os.path.exists(seed_script):
        print("  [seed] 后端无数据，运行 seed_all.py 造数 ...")
        try:
            subprocess.run(["python", seed_script], check=False,
                           timeout=REQUEST_TIMEOUT * 5,
                           cwd=REPO_ROOT)
            time.sleep(20)  # 等 spans 沉淀
        except Exception as e:  # noqa
            print("  [seed] 运行 seed_all.py 失败: %s" % e)
        data, biz, status = backend.get_data("/api/v1/sessions?size=1")
        return isinstance(data, dict) and isinstance(data.get("content"), list) \
            and len(data["content"]) >= min_sessions
    return False
