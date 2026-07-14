# mobile-ai-bank-demo 项目记忆

## 架构与端口
- Core: Java17+Spring Boot3.5.5+Spring AI Alibaba, :8080, ./mvnw
- 可观测后端(主): Java SB, observability/backend/, :9090, H2文件库
- 可观测前端: Vite+React, observability/frontend/, :3000 (proxy /api→127.0.0.1:9090)
- OTel Collector: observability/otel-collector/, :4318→127.0.0.1:9090
- LLM: DashScope qwen-plus / qwen-turbo

## 数据库(H2，非SQLite！)
- 活动库: observability/backend/data/observability.mv.db（CWD=backend 时 ./data 解析到此）。启动后端CWD必须是 observability/backend/。
- 项目根 data/ 已非活动库（旧2.2GB文件不存在）；直开文件看"空表"=看错文件，API仍可查全量。
- 查实时span用 GET /api/v1/traces/debug/span-stats（勿直开文件：独占锁+相对路径易错）。
- 数据流: Core(8080)→OTel SDK→Collector(4318)→Backend(9090)→H2

## 两套独立数据管道（易混淆，关键）
1. spans(链路追踪): Core OTel javaagent→OTLP→Collector→Backend→spans表，按traceId关联（一次HTTP请求=一个trace）。前端Trace列表读此。
2. sessions(会话回放): BankController处理完/chat后@Async调SessionBridge.reportSession→HTTP POST /api/v1/sessions→sessions+session_turns表，按sessionId关联（同会话多轮共享）。每请求必调、与OTel成败无关→会话回放永远有值、trace常空。
- 两管道弱关联: session_turns.traceId 存当轮traceId（单向引用，非依赖）。

## 核心惯例/运维SOP
- localhost→127.0.0.1（Windows IPv6优先导致refused）；纯展示文字例外。
- Maven: 用缓存 mvn `.m2/wrapper/dists/apache-maven-3.9.15/<hash>/bin/mvn -o package -DskipTests`；mvnw.jar缺失勿依赖mvnw。
- 沙箱不杀Java；用 nohup java -jar & 常驻。重启前先 netstat -ano+taskkill 释放jar锁（否则repackage重命名失败）。
- 启动后端/Core必带 --server.port=9090/8080（覆盖泄漏的SERVER_PORT=52785）。后端CWD=observability/backend/。
- **可复用的端到端回归脚本: `scripts/verify-E2E.sh`**（Git Bash）。一键清库→起 backend/collector/core→seed→断言 L0==L1 & L2≤L0 & L0:DomainRouter 存在。选项: `--skip-build / --verify-only / --stop-after / --turns N`。踩坑已修复: 原生程序路径须 `cygpath -w`(防 MSYS /d/ 转换失败+项目路径含空格); backend 必须 cd 到 backend 目录启动(H2 落点); 子 shell 内 `&` 会导致父 shell `$!` unbound。
- Core启动需OTel环境变量+javaagent: OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4318, OTEL_TRACES/METRICS/LOGS_EXPORTER=otlp, -javaagent:opentelemetry-javaagent.jar
- application.yml: management是顶级节点；models节6个LLM配置不能删；redis健康已disable。
- .ps1含中文必须UTF-8 with BOM（否则GBK乱码引号崩溃）。

## 已修复关键问题
- 🔴 traceId跨请求泄漏(98968ms本源): ObsChatModel makeCurrent()的Scope未同线程close→线程复用塌缩同traceId。call()用openScope+try-finally统一close；stream()装配线程不开Scope、doOn*只span.end()+新增doOnCancel兜底。验证:36请求→36 distinct traceId, duration全秒级(max3084ms), L2(12)≤L0(23)。教训: 任何makeCurrent()的Scope必须同线程配对close；Reactor doOn*不在装配线程，流式须持span引用操作。
- Trace列表空: 根span是OTel导出器span(非SERVER span)→修复为按trace_id去重枚举+跳过无业务span的trace。
- Trace意图/Agent显示会话整链+IO错乱(2026-07-13): buildTraceSummaryFromSpans改按L0边界切段取最后一段；详情IO优先SessionTurn.userMessage/aiResponse。
- @Modifying批量DELETE缺@Transactional→假成功(-1): AdminController.purge已补@Transactional；验收须断言删除计数≠-1。
- 指标: Timer必须publishPercentileHistogram(true)(否则导出Summary不被解析→TTFT恒0)；agent_performance/token_cost表无写入方勿读，实时聚合SpanEntity/metrics_agg。
- 🟢 遗留Bug#1已修复并验证(方案A,2026-07-14): DomainRouter确定性路由分支现显式经GlobalOpenTelemetry.getTracer("obs-chat-model")创建L0:DomainRouter span(routing.mode=deterministic,立即end,绝不makeCurrent防泄漏)，不再跳过L0→L0计数=请求数,L2≤L0恒成立。文档已同步GAP-v2 §8.18 / V6-整合 / V6-web / V4-整合 / V4-web(均含§4.2补正块+§14.3待定项A改为已实施)。运行时验证(清库+seed 36): l0Calls=36/l1Calls=36/l2Calls=12, distinctOpNames含 L0:DomainRouter, L0=L1=请求数, L2≤L0 ✓ 完全闭环。

## 文件目录规范
- docs/specs(需求/架构) docs/tests(用例) docs/plans(计划) scripts/(调试脚本) test/(测试脚本)
