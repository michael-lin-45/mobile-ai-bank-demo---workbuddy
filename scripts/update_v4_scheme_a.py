"""Update V4 Demo: scheme A as primary"""
path = r'd:/GitHub/mobile-ai-bank-demo - workbuddy/docs/specs/架构讨论/可观测优化总结-WorkBuddy-V4-Demo上线版-银行国产-0728.md'
with open(path, encoding='utf-8') as f:
    c = f.read()

changes = 0

# 1. Swap scheme A/B labels
s = '| | 方案 A：极简 | 方案 B：推荐 \u2b50 | 方案 C：完整 |'
r = '| | 方案 A：极简（推荐）\u2b50 | 方案 B：LLM 工程增强 | 方案 C：完整 |'
if s in c:
    c = c.replace(s, r)
    changes += 1
    print('1. Swapped scheme labels')

# 2. Swap positioning text
s = '快速 POC，最小演示 | 功能最全演示 | 最接近生产环境'
r = '快速 POC，核心链路完整 | +Prompt管理/评估/实验 | 最接近生产环境'
if s in c:
    c = c.replace(s, r)
    changes += 1

# 3. Fix component table header
s = '| 信号 | 方案 B（推荐）'
r = '| 信号 | 方案 A（推荐）\u2b50'
if s in c:
    c = c.replace(s, r)
    changes += 1

# 4. Replace Langfuse rationale
old_lf = '### 1.3 Langfuse Demo 可行性\n\n\u2705 docker compose 一键启动'
if old_lf in c:
    idx = c.find(old_lf)
    # find end marker (next ---)
    end = c.find('\n---', idx)
    new_lf = '### 1.3 为什么方案 A 不选 Langfuse\n\n> **Langfuse 是可选增强，不是必须**。以下四点说明：\n\n1. **会话回放 + 普通 Trace 排查**：Langfuse 完全可选，自研 H2 + 前端就能全覆盖\n2. **Agent Graph 流程图**：不是必须 Langfuse，自研前后端可完整实现，底层复用同一套 OTel Span 数据，无技术壁垒\n3. **方案 B 引入 Langfuse 的核心价值**：不是「看链路图」，而是补齐 Prompt 管理、LLM 自动评估、成本分析、实验数据集等 AI 工程能力；如果项目不需要 AI 迭代闭环，完全可以去掉 Langfuse，仅保留自研观测平台\n4. **成本取舍**：\n   - 去掉 Langfuse：少一套容器集群，架构极简，但需要少量前后端开发 Graph 绘图组件\n   - 保留 Langfuse：无需开发 Graph / 评估 / Prompt 功能，但增加存储、运维、评审成本\n\n> 方案 B（Langfuse docker compose）作为可选增强保留，详见 \u00a719。\n\n'
    c = c[:idx] + new_lf + c[end+1:]
    changes += 1
    print('4. Replaced Langfuse rationale')

# 5. Add scheme A architecture
old_title = '## 2. 系统目标架构（0728 Demo 版）\n\n### 2.1 方案 B 目标架构（推荐版）'
new_title = '## 2. 系统目标架构（0728 Demo 版）\n\n### 2.1 方案 A 目标架构（推荐版）\u2b50\n\n```mermaid\nflowchart TB\n    subgraph CORE[\"Core 应用 (8080)\"]\n        APP[\"BankController / Router\"]\n        AGENT[\"OTel Java Agent (L1)\"]\n        SAI[\"Spring AI Observation (L2)\"]\n        OBS[\"ObsChatModel 补 TTFT\"]\n        BIZ[\"Micrometer 业务指标 (L3)\"]\n        APP --> AGENT; APP --> SAI; APP --> OBS; APP --> BIZ\n    end\n    subgraph COLL[\"OTel Collector (4318)\"]\n        RECV[\"otlp receiver\"]\n        PROC[\"memory_limiter -> filter -> batch\"]\n        RECV --> PROC\n    end\n    subgraph BACK[\"自研 Backend (9090)\"]\n        API[\"OTLP Receiver + 查询 API + Prompt管理\"]\n        H2[(\"H2 文件库\")]\n        REDIS[(\"Redis (可选)\")]\n        API --> H2; API --> REDIS\n    end\n    subgraph UI[\"前端\"]\n        FE[\"自研前端 v24 — 7页 + Agent Graph 组件 + Prompt 管理\"]\n    end\n    AGENT --> RECV; SAI --> RECV; OBS --> RECV; BIZ --> RECV\n    PROC --> API\n    H2 --> FE; REDIS --> FE\n```\n\n### 2.2 方案 B 目标架构（LLM 工程增强版）'
if old_title in c:
    c = c.replace(old_title, new_title)
    changes += 1
    print('5. Added scheme A architecture')

# 6. Renumber
renums = [
    ('### 2.2 架构设计决策', '### 2.3 架构设计决策'),
    ('### 2.3 组件对接关系', '### 2.4 组件对接关系'),
    ('### 2.4 Demo 与生产差异', '### 2.5 Demo 与生产差异'),
]
for old, new in renums:
    if old in c:
        c = c.replace(old, new)
        changes += 1

# 7. Fix D3
c = c.replace('| D3 | 方案 B Langfuse docker compose', '| D3 | 方案 B（可选）Langfuse docker compose')

# 8. Fix section 3 title
c = c.replace('## 3. Demo 落地步骤（方案 B 推荐版）', '## 3. Demo 落地步骤（方案 A 推荐版）')

# 9. Fix production comparison table
c = c.replace('| Demo (方案B) |', '| Demo (方案A) |')

with open(path, 'w', encoding='utf-8') as f:
    f.write(c)
print(f'Done. {changes} changes applied')
