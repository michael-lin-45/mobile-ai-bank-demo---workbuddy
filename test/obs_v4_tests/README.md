# 可观测 V4 测试套件（`test/obs_v4_tests/`）

针对《可观测V4-详细设计-0719.md》中 **T-A ~ T-N** 任务的 API 集成 / E2E 测试，基于 **Python + requests**。

> 配套文档：`docs/tests/可观测V4-测试用例-0719.md`（55 条用例，含断言点与人工核查清单）。

---

## 1. 目录结构

```
test/obs_v4_tests/
├── common.py            # 公共：配置 / ApiClient / TestRunner / 断言助手 / 自播种
├── test_insights.py     # T-A InsightsEngine 6 API（+ T-L 边界字段）
├── test_alert.py        # T-B AlertEngine + T-F 告警验收（调度/抑制/状态机/LogNotifier）
├── test_collector.py    # T-C Collector filter/sampling/attributes + otelcol validate
├── test_sessions.py     # T-D T29 listSessions DB 分页
├── test_dead_keys.py    # T-E T30 清理 Redis 死 key（回归 + 人工 SQL）
├── test_frontend.py     # T-G 6 TAB / T-H 驾驶舱 / T-I 三态角标（数据契约）
├── test_metrics.py      # T-K 注册表 deepflux.* + T-J UpDownCounter
├── test_prompt_version.py # T-M 提示词版本化
├── test_reroute.py      # T-N reRoute 原报文透传
├── run_all.py           # 统一入口（按依赖顺序执行 + 汇总）
└── README.md
```

---

## 2. 依赖与前置

- **Python 3.8+**，安装依赖：
  ```bash
  pip install requests
  ```
- **后端** `127.0.0.1:9090` 已起（`BACKEND_URL` 可覆盖）。
- 部分用例还需 **Collector 4318 / Core 8080 / Redis 6379**（详见各模块头部注释）。
- **测试数据**：先播种（见 §4）。

---

## 3. 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `BACKEND_URL` | `http://127.0.0.1:9090` | 后端地址（含端口，不含 `/api/v1`） |
| `CORE_URL` | `http://127.0.0.1:8080` | Core 地址（自播种用） |
| `REQ_TIMEOUT` | `30` | 单请求超时（秒） |
| `VERBOSE` | `0` | `1` 打印每个请求 |
| `SEED_IF_EMPTY` | `0` | `1` 后端无数据时自动跑 `seed_all.py` |
| `ALERT_FULL` | `1` | `0` 跳过耗时的告警 firing/suppression 生命周期（仅跑契约检查） |
| `ALERT_CYCLE_WAIT` | `40` | 告警等待周期（秒，需 >30s 调度周期） |
| `ALERT_TEST_METRIC_KEY` | `request_count` | 注入规则的 metricKey（见 §6 假设） |
| `COLLECTOR_CONFIG` | `observability/otel-collector/config.yaml` | Collector 配置路径 |
| `OTELCOL_BIN` | PATH 探测 | `otelcol` 二进制路径（validate 用） |
| `PROMPT_VERSION_PATH` | `/api/v1/ai/prompt-versions` | T-M 端点（见 §6 假设） |

---

## 4. 运行

### 4.1 一键全跑（推荐验收）

```bash
# 前置：起 backend(9090) + collector(4318) + core(8080) + Redis，并 seed
bash scripts/verify-E2E.sh --skip-build      # 清库→起服务→seed→断言 L0==L1 & L2≤L0
python -m test.obs_v4_tests.run_all          # 随后跑 V4 测试套件
```

或直接让套件自播种：

```bash
cd "D:\GitHub\mobile-ai-bank-demo - workbuddy"
python test/obs_v4_tests/run_all.py --seed
```

### 4.2 单独跑某模块

```bash
python test/obs_v4_tests/test_insights.py
python test/obs_v4_tests/test_alert.py
BACKEND_URL=http://10.0.0.5:9090 python test/obs_v4_tests/test_sessions.py
```

### 4.3 run_all 参数

```bash
python test/obs_v4_tests/run_all.py --list                 # 列出模块
python test/obs_v4_tests/run_all.py --seed                 # 跑前 seed
python test/obs_v4_tests/run_all.py --quick                # 跳过告警耗时生命周期
python test/obs_v4_tests/run_all.py --modules insights,alert   # 只跑指定模块
```

退出码：`0` = 无 FAIL；`1` = 存在 FAIL。

---

## 5. 与 `scripts/verify-E2E.sh` 的协同（C 部分要求）

- **不改动** `verify-E2E.sh` 本体。两种方式接入：
  1. **串联**：`verify-E2E.sh --skip-build` 清库→起服务→seed→断言 L0==L1 & L2≤L0；随后 `run_all.py` 聚焦 T-A~T-N 新增契约，二者互补。
  2. **verify-only 衔接**：手动起服务 + `seed_all.py`，再 `run_all.py`（或带 `--seed` 让其自检补 seed）。
- 可在 `verify-E2E.sh` 的 seed 之后、最终断言之前插入 `python test/obs_v4_tests/run_all.py`（脚本层集成），保持 `verify-E2E.sh` 自身逻辑不变。

---

## 6. 关键假设（若不符 → 用例明确 FAIL，不假通过）

| 假设 | 位置 | 不符时表现 |
|---|---|---|
| 注入规则的 `metricKey` 默认 `request_count`（对应 Redis `request_count:6h`） | `test_alert.py` / `ALERT_TEST_METRIC_KEY` | TC-B-004 失败提示与工程师对齐 `collectMetricValue` 映射 |
| T-M REST 路径默认 `/api/v1/ai/prompt-versions` | `test_prompt_version.py` / `PROMPT_VERSION_PATH` | TC-M-001 返回 404 → 明确失败，提示改路径 |
| T-J/T-K 依赖后端暴露 actuator metrics（`/actuator/prometheus` 或 `/actuator/metrics`） | `test_metrics.py` | 未暴露则 SKIP，转人工/代码核查（不假通过） |
| `originalQuery` 按详细设计 §6.2 仅透传不持久化 | `test_reroute.py` TC-N-002a | 详情无该字段 → 软断言跳过（符合预期） |

---

## 7. 只能人工 / 日志核查项（脚本不假通过，给出口径）

见 `docs/tests/可观测V4-测试用例-0719.md` §11。摘要：

- **T-C tail_sampling 实际抽样率**：制造 >1s trace，查 Backend `spans` 留存；正常 trace 约 10% 留存（量级）。
- **T-C attributes 注入落库**：查入 Backend 的 span attributes 含 `env=production`/`service.version=1.0.0`。
- **T-B/F LogNotifier 未真实外发**：backend 日志含 `[Alert][NOTIFY]`；网络侧无对外 dingtalk POST 200。
- **T-B/F 抑制窗口内不重复 notify**：窗口内再次 breach，日志仅一次 `[Alert][NOTIFY]`。
- **T-D 真·DB 分页（无全量内存）**：注入 10k 会话，JProfiler/日志比对 `findAllByTimeRange` 已被替换；大 offset 耗时平稳。
- **T-E 5 死 key 缺失**：H2 Console 执行 `redis_metrics_snapshot` 查询（见 `test_dead_keys.py` 打印的 SQL），期望 0 行。
- **T-L 引擎只读 / 审计表空**：H2 查 `ai_action_audit`，表存在且 COUNT=0。
- **T-J +1/-1 实时语义**：Core 触发 interrupt→approve，观测 `pending_approval` Gauge 变化。
- **T-G/H/I 前端渲染**：`npm run dev` 人工查看 6 TAB / 驾驶舱 5 区块非占位；停 Collector 后三态角标变黄/红。

---

## 8. 通过标准（验收 gate）

1. 必做任务（T-A~T-I）脚本用例 **FAIL = 0**；
2. 告警 @Scheduled / 抑制 / 状态机（FIRING→ACKED→RESOLVED）均有真实事件佐证；
3. 分页 `totalElements/totalPages` 正确、大 offset 不 OOM、排序稳定；
4. T30 死 key 删除后主指标 API 不回归；
5. Collector `otelcol validate` 通过且健康检查 Span 不入 Backend；
6. 可选任务（T-J~T-N）完成契约/代码核查 + 可 API 验证部分；无 API 暴露部分以人工核查闭环。
