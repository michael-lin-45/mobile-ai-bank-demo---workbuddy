# mobile-ai-bank-demo 项目记忆

## 项目架构
- **Core**: Java 17 + Spring Boot 3.5.5 + Spring AI Alibaba, 端口 8080, Maven (./mvnw)
- **可观测后端(主)**: Java Spring Boot, `observability/backend/`, 端口 9090, H2 文件数据库
- **可观测后端(旧)**: Node.js 原生 http, `observability/backend/index.js`, 端口 9092（内嵌 HTML 面板，不再使用）
- **可观测前端**: Vite + React, `observability/frontend/`, 端口 3000, Vite Proxy `/api` → `http://127.0.0.1:9090`
- **OTel Collector**: `observability/otel-collector/`, 端口 4318, 转发到 `http://127.0.0.1:9090`
- LLM: DashScope qwen-plus / qwen-turbo

## 数据库架构
- **只有 H2，没有 SQLite！** 旧记录中的"SQLite"是误写
- Java 可观测后端使用 H2 文件数据库：`jdbc:h2:file:./data/observability`（相对路径，取决于 Java 进程工作目录）
- H2 文件位置**取决于 Java 进程工作目录(CWD)**，非固定：
  - **当前活动库（2026-07-10 实测）**：`observability/backend/data/observability.mv.db`（约 10MB，运行期持续写入，mtime 随写入更新）。原因：start-all.ps1 从 `observability/backend/` 启动 → `./data` 解析为 `observability/backend/data/`。
  - ⚠️ **项目根 `data/observability.mv.db` 已非活动库**（旧记录写的 2.2GB 文件已不存在，该目录现仅 `observability.trace.db` ~2KB）。若用 H2 查看器打开项目根 `data/` 会看到"空表"——这是**看错文件，数据没丢**，API 仍能查到全量 span。
  - **查实时 span 请用 API 而非直开文件**：H2 MVStore 运行期持独占锁无法直读，且相对路径易指错。正确做法：`GET /api/v1/traces/debug/span-stats`（返回 `totalSpans` / `distinctOpNames`）。
- 数据流: Core(8080) → OTel SDK → Collector(4318) → Java Backend(9090) → H2

### 两套独立的可观测数据管道（关键！易混淆）
可观测后端有**两条互不依赖**的数据写入管道，关联键完全不同：
1. **spans 管道（链路追踪）**：Core OTel javaagent 自动拦截 → OTLP → Collector(4318) → Backend → 写 `spans` 表。按 **traceId** 关联（一次 HTTP 请求 = 一个 trace）。前端「链路追踪/Trace 列表」读此表。
2. **sessions 管道（会话回放）**：Core `BankController` 处理 `/api/bank/chat` 完成后，**异步**调 `SessionBridge.reportSession(...)`（`@Async`，fire-and-forget，失败不影响主业务）→ `HttpClient` **HTTP POST `http://127.0.0.1:9090/api/v1/sessions`** → Backend `SessionService.upsertSession` 写 **`sessions` 表 + `session_turns` 表**。按 **sessionId** 关联（同一次会话的多轮对话共享 sessionId）。
- **为什么"会话回放永远有值、trace 经常空"**：sessions 是 Core 每请求必调的显式桥接，与 OTel 导出成败无关；spans 依赖更长的 OTel 导出链 + 旧根 span 识别逻辑（R24 已修），更易空。
- **几轮会话如何关联**：靠 sessionId。`upsertSession` 按 sessionId upsert → `turnCount+1`、`intentFlow = flow+" → "+intent`（追加意图链）、`session_turns` 追加一条 turn。**完全不依赖 span / 根 span / traceId**。
- **两管道的弱关联**：`session_turns.traceId` 存当轮对应 OTel traceId（Core 传入），理论上可从会话回放跳转到 trace，但这是单向引用而非依赖——session 不靠 trace 存在，反之亦然。
- ⚠️ 副作用（**已修复 2026-07-13**）：trace 列表 `intent` 曾从 Session 表 `intentFlow` 回退取值，显示成整条会话意图长链（如 "WEALTH→…→TRANSFER"）。现改用 `buildTraceSummaryFromSpans`：按 L0 边界切段、取**最后一段**业务链作为单请求视角（`intent` + `agentChain`），不再回退 Session 整链。

## 核心惯例

### 1. Windows 环境 localhost → 127.0.0.1（重要！）
所有配置文件、脚本、健康检查、API 调用中的 `localhost` 必须替换为 `127.0.0.1`。
原因: Windows DNS 解析 `localhost` 优先返回 IPv6 `[::1]`，与 `0.0.0.0` 绑定的 IPv4 服务协议不匹配 → Connection Refused / Timeout。
例外: 纯展示文字(Write-Host, console.log) 保留 localhost 无影响。

### 2. Maven 构建避坑（本环境）
- maven 安装：`D:\Program Files\apache-maven-3.9.16`（系统可用）。**必须用 `mvn.cmd`（PowerShell/cmd）**，不要 Git Bash 的 `mvn` sh 脚本——后者在 MSYS 下 classworlds 路径装配失败，会误报 `ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher`，让人误以为 maven 坏了。
- `mvnw` shell 脚本用 Java hashCode ≠ `mvnw.cmd` 用 SHA-256 做缓存目录名；`.mvn/wrapper/maven-wrapper.jar` 缺失 → 优先用系统 `mvn.cmd` 或缓存的 `~/.m2/wrapper/dists/apache-maven-3.9.15/.../bin/mvn`，不要依赖 `mvnw`。
- Windows PowerShell 中优先用 `./mvnw.cmd`（避免 hash 不匹配导致下载失败）
- pom.xml 配置 jvmArguments 优于命令行传参（避免嵌套引号 + 路径空格问题）
- ⚠️ **当前 `.mvn/wrapper/maven-wrapper.jar` 缺失**（离线无法自举）→ 直接用缓存 Maven 编译：`C:/Users/Administrator/.m2/wrapper/dists/apache-maven-3.9.15/<hash>/bin/mvn -o package -DskipTests`（`-o` 离线，依赖已缓存；`<hash>` 目录名每次可 `ls` 确认）。

### 3. PowerShell Start-Process 传参
- 首选方案: `Start-Process <exe> -ArgumentList @("arg1", $pathWithSpace, ...) -WorkingDirectory $ROOT`，数组形式让 PowerShell 自动为含空格参数加引号，无需手动转义
- 备选: 路径含空格时用 Base64 UTF-16LE + `-EncodedCommand`（需嵌套 PowerShell 时）
- npm.cmd 等批处理文件不能直接 `Start-Process`，需包装在 `powershell -NoExit -Command` 中

### 4. PowerShell 文件编码（关键！）
- **所有含中文的 .ps1 文件必须存为 UTF-8 with BOM**（`EF BB BF`）
- 原因: Windows PowerShell 5.1 读取无 BOM 文件时默认用系统 ANSI 代码页(GBK)，UTF-8 多字节中文会变成乱码，导致引号/括号匹配崩溃、报"字符串缺少终止符"等莫名其妙错误
- 例外: 纯 ASCII 的 ps1 文件 BOM 可选

### 5. application.yml 结构
- `management:` 是顶级节点，不要放在 `spring:` 下面（Spring Boot 找不到）
- `models:` 节包含 6 个 LLM 模型配置，不能删除（Bean 创建依赖）

### 6. ❌ 旧结论已推翻：沙箱不会杀 Java 进程（别再当"不能自主重启"的借口）
- **错误旧记**：曾以为 WorkBuddy 沙箱会干掉长时间运行的 Java 进程，所以只能让用户手工跑 start-all.ps1 重启。这是**误判，已纠正**。
- **真相**：沙箱不杀 Java。之前"进程消失/启动失败"是因为用了 `cmd //c "... &"`（前台 cmd 子进程，工具调用结束被回收），不是沙箱杀进程。改用 **`nohup java -jar ... &`（bash 后台脱离）** 即可常驻——已实测 backend(PID 1892)+core(PID 33104) 跨多次工具调用存活，与 collector/frontend 一起稳定运行。
- **结论**：Agent 可自主用 nohup 重启服务，**不要再对用户说"沙箱限制我无法启动服务，请你手工执行 start-all.ps1"**。具体命令见 §11。

### 10. 通过 Agent 重启可观测后端必须显式指定端口（重要！）
- **现象**：Agent 的 PowerShell 会话环境会继承 `SERVER_PORT=52785`（WorkBuddy 主程序自身端口），导致 `Start-Process` 拉起的 `java -jar` 把 `SERVER_PORT` 当作 `server.port` → 后端绑到 **52785** 而非 9090，并与 WorkBuddy 冲突启动失败（日志：`Port 52785 was already in use`）。`application.yml` 里 `server.port: 9090` 是对的，但被环境变量覆盖。
- **正确启动命令**（CWD 必须是 `observability/backend/`，否则 H2 `./data` 相对路径解析错）：
  `java -jar target/observability-backend-0.1.0-SNAPSHOT.jar --server.port=9090`（`--server.port` CLI 参数优先级高于环境变量，可覆盖泄漏的 `SERVER_PORT`）。
- 必须用 `Start-Process -WorkingDirectory $ROOT -WindowStyle Hidden` 拉成独立进程，否则会话结束/沙箱回收会被杀。日志重定向到 `logs/backend-out.log`、`logs/backend-err.log`，PID 写入 `logs/backend-pid.txt`。

### 11. 服务自主重启 SOP（Agent 直接执行，无需用户手工跑 start-all.ps1）
> 核心认知：用 bash 的 `nohup java -jar ... &` 脱离终端启动即可常驻，沙箱不杀 Java。
> 启动前必须**先停旧进程释放 jar 锁**，否则 `mvn package` 的 repackage 阶段因 jar 被占用重命名失败（编译本身会通过，仅打包失败）。

**① 查端口→PID，并停进程**
```bash
netstat -ano 2>/dev/null | grep -E ':9090|:8080|:4318|:3000|:6379'
taskkill /F /PID <backend_pid> /PID <core_pid>
```
- 不要用 PowerShell `Get-CimInstance Win32_Process` / `netstat | Select-String`（本环境常被吞输出）；`tasklist` 经 bash 会乱码。统一用 `netstat -ano` + `taskkill`，最稳。

**② Maven 打包（两模块独立，可并行后台）**
```bash
# 后端
cd "D:/GitHub/mobile-ai-bank-demo - workbuddy/observability/backend" && ./mvnw.cmd package -DskipTests -q
# Core
cd "D:/GitHub/mobile-ai-bank-demo - workbuddy" && ./mvnw.cmd package -DskipTests -q
```
- `./mvnw.cmd` 在 Git Bash 下也能调起（cmd 解析），`-q` 静默、EXIT=0 即成功。
- 仅想编译校验、不碰运行中的 jar，用 `compile` 代替 `package`。

**③ 重启后端（CWD 必须是 observability/backend，否则 H2 `./data` 相对路径错）**
```bash
cd "D:/GitHub/mobile-ai-bank-demo - workbuddy/observability/backend" && \
nohup java -jar target/observability-backend-0.1.0-SNAPSHOT.jar --server.port=9090 \
  > "D:/GitHub/mobile-ai-bank-demo - workbuddy/scripts/logs/backend-restart.log" 2>&1 &
# 等 ~30s（Spring Boot 启动约 26s）后健康检查
curl -s -m 5 http://127.0.0.1:9090/health
```

**④ 重启 Core（带 OTel 环境变量 + javaagent + 显式端口）**
```bash
cd "D:/GitHub/mobile-ai-bank-demo - workbuddy" && \
export OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4318 \
       OTEL_SERVICE_NAME=mobile-bank-core \
       OTEL_METRICS_EXPORTER=otlp OTEL_TRACES_EXPORTER=otlp OTEL_LOGS_EXPORTER=otlp && \
nohup java -Xms512m -Xmx1024m -XX:MaxMetaspaceSize=256m \
  -javaagent:"D:/GitHub/mobile-ai-bank-demo - workbuddy/opentelemetry-javaagent.jar" \
  -jar "target/mobile-ai-demo-0.0.1-SNAPSHOT.jar" --server.port=8080 \
  >> core.log 2>&1 &
# 等 ~45-50s 后健康检查
curl -s -m 5 http://127.0.0.1:8080/actuator/health
```

**⑤ 一键验证清单**
- 端口存活：`netstat -ano | grep -E ':9090|:8080'`
- 健康：上面两个 curl
- 指标分位（确认无 98968ms 异常）：`GET /api/v1/metrics/realtime`
- trace 换行扫描：`GET /api/v1/traces?limit=200` 后检查 `sessionId` 是否含 `\n`（应为 0）
- 注意：`--server.port=9090/8080` 必须显式带，覆盖可能泄漏的 `SERVER_PORT` 环境变量（WorkBuddy 用 52785）。

## 已修复的关键问题
- Maven Wrapper hash 不一致 → 创建 symlink + 优先用 mvnw.cmd
- OTel javaagent 路径空格 → pom.xml 用 `&quot;` 包裹
- Redis 健康检查 DOWN → `management.health.redis.enabled: false`(顶级节点)
- OTel OTLP endpoint → `/v1/*` 路径(非 `/api/v1/*`)
- observability backend 端口不一致 → 9092
- SseAdapter toJson → 返回 ResponseEntity<WorkflowOutput> 替代 Object
- start-all.ps1 4处 Start-Process 嵌套引号导致路径空格截断 → 统一用 Base64 EncodedCommand
- Core DNS 无法解析 dashscope.aliyuncs.com → start-all.ps1 自动检测系统 DNS 并注入 JVM 参数
- **Spring Data JPA `@Modifying` 批量 DELETE 必须加 `@Transactional`**：否则继承 `SimpleJpaRepository` 类级 `readOnly=true`，在只读事务里执行 DELETE 会抛异常（删除不生效，被 `safeDelete` 吞掉返回 -1 造成"假成功"）。`AdminController.purge` 清理 4 表的 `@Modifying deleteByX` 已补 `@Transactional` 修复。验收脚本断言必须检查删除计数非 -1，不能只看 HTTP 200。
- **链路追踪列表为空 bug（已修复）**：`TraceQueryService.listTraces/listTracesPaginated` 原依赖"根 span（parentSpanId IS NULL）当 trace 入口"，但本仓库数据里所有根 span 都是 Core OTel 导出器的 OTLP 导出 span（`POST` CLIENT），真实的 `POST /api/bank/chat` SERVER span 未被导出/命名 → fallback 查 `SERVER + 'POST %'` 返回 0 → 列表空。修复为**按 `trace_id` 去重枚举**（`findDistinctTraceIdsSince`）+ 对每个 trace 的 spans 聚合（跳过无业务 span 的 trace）。前端 Trace 列表只显示真正调过 LLM 的 trace（规则命中无 span 不显示，属待定项 A 范畴）。
- **Trace 列表意图/Agent 显示会话内容 + 详情 IO 错乱（2026-07-13 修复）**：原 `buildTraceListVOFromSpans` 调 `buildIntentChainFromSpans` 把同 traceId 多轮复用的全部 L0 段拼成整条会话链（如 `BILL Domain → … → TRANSFER → … → CHAT`），且详情 IO 卡片被 L0 路由器内部 prompt/response 覆盖。`buildTraceSummaryFromSpans` 改为按 L0 边界切段、取**最后一段**业务链作为单请求视角（`intent` + `agentChain`）；详情 IO 优先 `SessionTurn.userMessage/aiResponse`（用户原始请求/Agent 最终输出），仅缺失时兜底取最终执行层 L2。前端 `TraceTable` 意图 Badge 补 `BILL_QUERY/UNSUPPORTED` 映射；详情输入卡 label 改「用户原始请求」。

### 7. Core 启动 JVM DNS 配置（重要！）
- Core 使用 Reactor Netty DNS 解析器，沙箱中默认 DNS (192.168.1.1) 无法解析外部域名
- start-all.ps1 已更新：通过 `Get-DnsClientServerAddress -AddressFamily IPv4` 自动获取系统 DNS，注入 `-Dreactor.netty.dns.nameservers=<动态>` + `-Djava.net.preferIPv4Stack=true`
- 无需硬编码 IP 地址
- 备用方案: `-Dsun.net.spi.nameservice.nameservers=...` (仅对 JNDI DNS 有效，Netty 不受影响)

### 8. 项目文件目录规范
- `.\docs\specs\` — 项目需求文档、架构设计说明书
- `.\docs\tests\` — 测试用例集、测试说明书
- `.\docs\plans\` — 项目 Plan 等工程计划文档
- `.\scripts\` — 开发/调试临时脚本(ps1/py/js/cmd)，日志子目录 `.\scripts\logs\`
- `.\test\` — 测试脚本(ps1/py/sh/spec.js)，日志子目录 `.\test\logs\`
- `.\test\test-results\` — 测试结果、测试报告
- Playwright 输出配置在 `playwright.config.js` → `testDir: '.', testMatch: 'test/**/*.spec.js', outputDir: 'test/test-results'`
- 正常业务代码（java/xml/html/jsx 等）按常规项目结构保存，不受上述限制

### 9. 指标发射与聚合惯例（重要，易踩坑）
- **Core 侧 Timer 必须 `publishPercentileHistogram(true)`**：`Timer.publishPercentiles(...)` 会被 Micrometer OTLP 导出器导出为 **OTLP Summary** 类型；而 Backend `OtlpParser.parseMetrics` 只解析 Histogram/Sum/Gauge，**完全不解析 Summary** → 分位数指标（如首 Token 时延 TTFT）永远为 0。要导出真正的 OTLP Histogram 必须用 `publishPercentileHistogram(true)`（见 `ObsChatModel.buildTimer`）。
- **不要读"全库无写入方"的静态表**：`agent_performance` / `token_cost` 等表在 schema.sql 有定义但全代码库无 INSERT → 读它们永远为空。正确做法是**实时聚合**：调用次数/耗时分位/错误率从 `SpanEntity`（每个 LLM 调用一条业务 span，attributes 含 `model.name/agent.name/agent.level/intent`）聚合；Token 总量/TTFT/TPOT 从 H2 `metrics_agg` 取（`llm.token.input/output` 为 cumulative 取窗口 MAX，`llm.first_token.latency`/`llm.token.per.output.time` 为 Histogram 样本）。
- **改指标相关的 H2 表结构要谨慎**：`application.yml` 设 `ddl-auto: none`，表由 `schema.sql` 管理；加列需改 schema.sql 并 ALTER 现有运行期 `.mv.db`（有风险，且运行期持独占锁无法直接操作）。优先复用现有表（SpanEntity + metrics_agg）而非加列。
- **避免改 H2 表结构**：若只需新增聚合维度，优先用现有 Span attributes JSON + metrics_agg 的 tags JSON 提取（如 `extractTagValue(tags,"agent.name")`），不要新增列。
