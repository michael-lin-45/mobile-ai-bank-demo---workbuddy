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
- H2 文件位于 `observability/backend/data/observability.mv.db`（53KB）和 `data/observability.mv.db`（2.2GB）
- 数据流: Core(8080) → OTel SDK → Collector(4318) → Java Backend(9090) → H2

## 核心惯例

### 1. Windows 环境 localhost → 127.0.0.1（重要！）
所有配置文件、脚本、健康检查、API 调用中的 `localhost` 必须替换为 `127.0.0.1`。
原因: Windows DNS 解析 `localhost` 优先返回 IPv6 `[::1]`，与 `0.0.0.0` 绑定的 IPv4 服务协议不匹配 → Connection Refused / Timeout。
例外: 纯展示文字(Write-Host, console.log) 保留 localhost 无影响。

### 2. Maven Wrapper 避坑
- `mvnw` shell 脚本用 Java hashCode ≠ `mvnw.cmd` 用 SHA-256 做缓存目录名
- Windows PowerShell 中优先用 `./mvnw.cmd`（避免 hash 不匹配导致下载失败）
- pom.xml 配置 jvmArguments 优于命令行传参（避免嵌套引号 + 路径空格问题）

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

### 6. sandbox 限制
- WorkBuddy 沙箱会干掉长时间运行的 Java 进程（访问 AppData\Crypto\RSA 被拦截）
- 测试建议在用户的 PowerShell 窗口手动启动，不用后台任务

## 已修复的关键问题
- Maven Wrapper hash 不一致 → 创建 symlink + 优先用 mvnw.cmd
- OTel javaagent 路径空格 → pom.xml 用 `&quot;` 包裹
- Redis 健康检查 DOWN → `management.health.redis.enabled: false`(顶级节点)
- OTel OTLP endpoint → `/v1/*` 路径(非 `/api/v1/*`)
- observability backend 端口不一致 → 9092
- SseAdapter toJson → 返回 ResponseEntity<WorkflowOutput> 替代 Object
- start-all.ps1 4处 Start-Process 嵌套引号导致路径空格截断 → 统一用 Base64 EncodedCommand
- Core DNS 无法解析 dashscope.aliyuncs.com → start-all.ps1 自动检测系统 DNS 并注入 JVM 参数

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
