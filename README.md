# Mobile AI Bank — 可观测系统

手机银行 AI 助手全链路可观测平台，含 Core(聊天引擎)、Backend(数据采集)、Frontend(可视化大屏)。

## 快速启动

```powershell
.\scripts\start-all.ps1      # 启动全栈 (Redis + Backend + Collector + Core + Frontend)
.\test\run_all.ps1            # 运行全部测试 (API + Playwright 前端)
.\scripts\stop-all.ps1        # 停止全栈
```

可选参数：

```powershell
.\test\run_all.ps1 -ApiOnly   # 仅 API 测试
.\test\run_all.ps1 -WebOnly   # 仅前端测试
```

## 服务端口

| 服务 | 端口 | 说明 |
|------|:----:|------|
| Redis | 6379 | 缓存 |
| Backend | 9090 | 可观测后端 (Spring Boot + H2) |
| Collector | 4318 | OTel Collector |
| Core | 8080 | 手机银行聊天引擎 (Spring Boot + Spring AI) |
| Frontend | 3000 | 可视化大屏 (Vite + React + Ant Design) |

## 目录结构

```
├── docs/                  # 文档
│   ├── specs/             #   需求、架构设计说明书
│   ├── tests/             #   测试用例集、测试说明书
│   └── plans/             #   项目计划
├── scripts/               # 运维 / 调试脚本
│   ├── start-all.ps1      #   一键启动全栈
│   ├── stop-all.ps1       #   一键停止全栈
│   └── logs/              #   启动日志
├── test/                  # 测试脚本
│   ├── run_all.ps1        #   一键运行全部测试
│   ├── test_api_full.py   #   API 全量测试 (45 用例)
│   ├── test_frontend.spec.js # Playwright 前端测试 (19 用例)
│   ├── logs/              #   测试日志
│   └── test-results/      #   测试结果
├── observability/         # 可观测系统
│   ├── backend/           #   Spring Boot 后端
│   ├── frontend/          #   React 前端
│   └── otel-collector/    #   OTel Collector
├── src/                   # Core 聊天引擎源码
└── target/                # Core 构建产物
```

## 日志

- 启动日志: `scripts/logs/`
- 测试日志: `test/logs/`
- Core 运行日志: `scripts/logs/core-startup.log`
