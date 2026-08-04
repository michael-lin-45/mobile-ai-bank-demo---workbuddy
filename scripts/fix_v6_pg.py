#!/usr/bin/env python3
"""Fix PG/Langfuse references in V6 production doc"""
import re

path = r'd:/GitHub/mobile-ai-bank-demo - workbuddy/docs/specs/架构讨论/可观测优化总结-WorkBuddy-V6-生产上线版-银行国产-0728.md'
with open(path, encoding='utf-8') as f:
    content = f.read()

counts = {}

# Replace PG references with 信创 DB
reps = [
    ('落 PG ', '落信创 DB '),
    ('**PG** 管真相', '**信创 DB** 管真相'),
    ('**PG** COUNT', '**信创 DB** COUNT'),
    ('**PG** + layer', '**信创 DB** + layer'),
    ('**PG** agent.', '**信创 DB** agent.'),
    ('**PG** 读时算', '**信创 DB** 读时算'),
    ('R["PG"]', 'R["信创DB"]'),
    ('P0 DDL（**PG 已就位**）', 'P0 DDL（**信创 DB 已就位**，MySQL/openGauss/Oracle 三套方言）'),
    ('**PG**(Patroni)', '**信创 DB**（按银行实际选型）'),
    ('**PG** 16C64Gx3', '**信创 DB** 16C64Gx3'),
    ('PostgreSQL SSD', '信创 DB SSD'),
    ('**PG** 压缩分区', '**信创 DB** 压缩分区'),
    ('冷层 **PG**', '冷层 信创 DB'),
    ('落 **PG** ', '落 **信创 DB** '),
    ('/**PG** Consumers', '/信创 DB Consumers'),
]

for old, new in reps:
    c = content.count(old)
    if c > 0:
        content = content.replace(old, new)
        counts[old[:40]] = c

# Fix §0.2 core decisions
content = content.replace(
    'P0：H2→PostgreSQL 迁移（银行量级必须）',
    'P0：信创 DB 建表 + 数据就位（银行量级必须）')
content = content.replace(
    'P0：H2→PostgreSQL 迁移',
    'P0：信创 DB 建表')
content = content.replace(
    'P1：Collector 处理器增强 + 标准栈（Tempo/VM/Loki/Grafana）信号外溢',
    'P1：Collector 处理器增强 + 标准栈（Tempo/VM/Loki/Grafana，方案A）信号外溢')
content = content.replace(
    'P2：Kafka 缓冲层 + 告警迁 Grafana + 前端终稿 + **Langfuse 接入**。',
    'P2：Kafka 缓冲层 + 告警迁 Grafana + 前端终稿 + **MLflow 接入**（替代 Langfuse）。')
content = content.replace(
    'H2→PostgreSQL 迁移（银行量级必须）',
    '信创 DB 建表 + 数据就位（银行量级必须）')

# Fix §14.3
content = content.replace(
    'P2 削峰+HA+Langfuse',
    'P2 削峰+HA+MLflow')

# Fix §16.4 Langfuse decision
content = content.replace(
    '待定项 C（是否引入 Langfuse）：\U0001f7e2 **已确认需要**（2026-07-18），**生产 P2 必装**。',
    '待定项 C（是否引入 Langfuse）：\U0001f7e2 **已排除**（0728：银行无 PG，改用 MLflow+自研+DeepEval，见 \xa716.3/\xa717）。')

# Fix web articles table P20
content = content.replace(
    '| P20 提示词即 Runbook | N9 | \xa79 + **Langfuse** |',
    '| P20 提示词即 Runbook | N9 | \xa79 + 自研/MLflow Prompt Registry |')

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)

print(f'Fixed {sum(counts.values())} PG/Langfuse references:')
for k, v in counts.items():
    print(f'  {v}x: {k}')
