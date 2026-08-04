"""Sync V4 §6.2-6.4 and §9.4-9.9 to V6"""
import re

v4_path = r'd:/GitHub/mobile-ai-bank-demo - workbuddy/docs/specs/架构讨论/可观测优化总结-WorkBuddy-V4-Demo上线版-银行国产-0728.md'
v6_path = r'd:/GitHub/mobile-ai-bank-demo - workbuddy/docs/specs/架构讨论/可观测优化总结-WorkBuddy-V6-生产上线版-银行国产-0728.md'

with open(v4_path, encoding='utf-8') as f:
    v4 = f.read()
with open(v6_path, encoding='utf-8') as f:
    v6 = f.read()

# Extract V4 §6.2-6.4 (from "### 6.2 链路追踪" to "## 7. DB")
s62_start = v4.find('### 6.2 链路追踪')
s7_start = v4.find('## 7. DB 表设计')
v4_s62_s64 = v4[s62_start:s7_start].strip()

# Extract V4 §9.4-9.9 (from "### 9.4 三大诊断域" to "### 9.10 0728 审计")
s94_start = v4.find('### 9.4 三大诊断域')
s910_start = v4.find('### 9.10 0728 审计')
v4_s94_s99 = v4[s94_start:s910_start].strip()

# Find insertion points in V6
# 1. §6.2-6.4: insert after "### 6.1 总览大屏" subsections, before "### 6.5 审计"
# Actually V6 §6 structure is different. Let me find the right spot.
s61_v6 = v6.find('### 6.1 总览大屏')
s65_v6 = v6.find('### 6.5 0728')

if s61_v6 > 0 and s65_v6 > 0:
    # Find the --- separator before §6.5
    sep_before_s65 = v6.rfind('---', s61_v6, s65_v6)
    head = v6[:sep_before_s65]
    tail = v6[s65_v6:]
    v6 = head + '\n\n' + v4_s62_s64 + '\n\n---\n\n' + tail
    print(f'Inserted V4 §6.2-6.4 after V6 §6.1')

# 2. §9.4-9.9: insert after V6 §9.3 content, before "### 9.6 0728 审计"
s93_v6 = v6.find('### 9.3 InsightsEngineService')
s96_v6 = v6.find('### 9.6 0728')
if s93_v6 > 0 and s96_v6 > 0:
    sep = v6.rfind('---', s93_v6, s96_v6)
    head = v6[:sep]
    tail = v6[s96_v6:]
    v6 = head + '\n\n' + v4_s94_s99 + '\n\n---\n\n' + tail
    print(f'Inserted V4 §9.4-9.9 after V6 §9.3')

# Fix header numbering for consistency
v6 = v6.replace('### 6.5 0728', '### 6.6 0728')
v6 = v6.replace('### 9.6 0728', '### 9.11 0728')

with open(v6_path, 'w', encoding='utf-8') as f:
    f.write(v6)
print('V6 sync complete')
