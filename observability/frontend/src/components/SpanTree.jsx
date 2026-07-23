import React, { useState } from 'react';

/**
 * SpanTree — Agent→LLM 嵌套结构（参考 v16 trace-tree）
 *
 * 将后端返回的扁平 spanTree 重组为 Agent 层级结构：
 * - L0 Agent (蓝色) → 内含 L0 LLM 调用行
 * - L1 Agent (紫色) → 内含 L1-LLM1, L1-LLM2 调用行
 * - L2 Agent (绿色) → 内含 L2 LLM 调用行
 *
 * Agent header 有"详情"按钮 → 展开 Agent IO 面板
 * LLM row 有"详情"按钮 → 展开 LLM Prompt/Response 面板
 */
function SpanTree({ nodes, reRouted, reRoutePath }) {
  const agents = buildAgentTree(nodes);

  if (agents.length === 0) {
    return (
      <div style={{ padding: 16, color: 'rgba(0,0,0,.45)', fontSize: 12 }}>
        暂无 Span 数据
      </div>
    );
  }

  return (
    <div>
      {reRouted && (
        <div style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          padding: '8px 12px',
          marginBottom: 12,
          borderRadius: 6,
          background: '#fff7e6',
          border: '1px solid #ffd591',
          fontSize: 12,
          color: '#d46b08',
        }}>
          <span style={{ fontWeight: 700 }}>↻ 重路由 (reRoute)</span>
          <span style={{ color: 'rgba(0,0,0,.65)' }}>
            L0 / L1 选择有误，L1 / L2 已将请求退回 L0 重新路由。
            {reRoutePath ? (
              <span style={{ fontFamily: '"JetBrains Mono", monospace', marginLeft: 6 }}>
                路径：{reRoutePath}
              </span>
            ) : null}
          </span>
        </div>
      )}
      {agents.map((agent, idx) => (
        <React.Fragment key={idx}>
          {idx > 0 && <AgentConnector />}
          <AgentBlock agent={agent} />
        </React.Fragment>
      ))}
    </div>
  );
}

/** Agent连接器：L0→L1、L1→L2之间的虚线连接 */
function AgentConnector() {
  return (
    <div style={{
      height: 18,
      borderLeft: '2px dashed #d9d9d9',
      marginLeft: 26,
      position: 'relative',
    }}>
      <span style={{
        position: 'absolute',
        left: 8,
        top: -2,
        fontSize: 10,
        color: 'rgba(0,0,0,.45)',
        fontFamily: '"JetBrains Mono", monospace',
        whiteSpace: 'nowrap',
      }}>
        ↓
      </span>
    </div>
  );
}

/** Agent 块：header + IO面板 + body(LLM行) */
function AgentBlock({ agent }) {
  const [expanded, setExpanded] = useState(true);
  const [ioOpen, setIoOpen] = useState(false);

  const layerColor = getLayerColor(agent.layer);
  const isError = agent.statusCode === 'ERROR';

  return (
    <div style={{ marginBottom: 0 }}>
      {/* Agent Header */}
      <div
        onClick={() => setExpanded(!expanded)}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          padding: '10px 14px',
          borderRadius: 6,
          cursor: 'pointer',
          border: '1px solid #f0f0f0',
          marginBottom: 2,
          transition: 'all .15s',
          background: layerColor.bg,
        }}
        onMouseEnter={(e) => { e.currentTarget.style.borderColor = layerColor.text; }}
        onMouseLeave={(e) => { e.currentTarget.style.borderColor = '#f0f0f0'; }}
      >
        <span style={{
          fontSize: 10,
          color: 'rgba(0,0,0,.45)',
          transition: 'transform .2s',
          transform: expanded ? 'rotate(0deg)' : 'rotate(-90deg)',
        }}>▼</span>

        {/* 层级 Badge */}
        <span style={{
          display: 'inline-block',
          padding: '2px 8px',
          borderRadius: 4,
          fontSize: 11,
          fontWeight: 600,
          background: isError ? '#fff2f0' : layerColor.tagBg,
          color: isError ? '#ff4d4f' : layerColor.tagText,
        }}>
          {agent.layer}
        </span>

        {/* follow-up 徽标：L1 未经过 L1-LLM2 */}
        {agent.isFollowUp && (
          <span style={{
            display: 'inline-block',
            padding: '2px 8px',
            borderRadius: 4,
            fontSize: 10,
            fontWeight: 600,
            background: '#fff7e6',
            color: '#d46b08',
            border: '1px solid #ffd591',
          }}>
            FOLLOW-UP ↪ 跳过 L1-LLM2
          </span>
        )}

        {/* Agent 名称 */}
        <span style={{ fontWeight: 600, fontSize: 13, color: 'rgba(0,0,0,.88)' }}>
          {agent.name}
        </span>

        {/* Agent 描述 */}
        {agent.desc && (
          <span style={{ fontSize: 12, color: 'rgba(0,0,0,.65)' }}>
            {agent.desc}
          </span>
        )}

        {/* 耗时 */}
        <span style={{
          marginLeft: 'auto',
          fontFamily: '"JetBrains Mono", monospace',
          fontSize: 12,
          fontWeight: 600,
          color: layerColor.text,
        }}>
          {(agent.totalDurationMs || agent.durationMs || 0)}ms
        </span>

        {/* token 小参数 */}
        {(() => {
          const a = agent.attributes || {};
          const inT = a['ai.token.input'];
          const outT = a['ai.token.output'];
          if (inT != null || outT != null) {
            return (
              <span style={{
                fontFamily: '"JetBrains Mono", monospace',
                fontSize: 10,
                color: 'rgba(0,0,0,.55)',
                background: '#fff',
                border: '1px solid #f0f0f0',
                borderRadius: 4,
                padding: '1px 6px',
              }}>
                🪙 {inT != null ? inT : '?'} / {outT != null ? outT : '?'}
              </span>
            );
          }
          return null;
        })()}

        {/* 详情按钮（始终显示） */}
        <span
          onClick={(e) => {
            e.stopPropagation();
            setIoOpen(!ioOpen);
          }}
          style={{
            display: 'inline-block',
            padding: '2px 8px',
            borderRadius: 4,
            fontSize: 10,
            fontWeight: 600,
            border: '1px solid #1677ff',
            color: '#1677ff',
            background: ioOpen ? '#e6f4ff' : '#fff',
            cursor: 'pointer',
            whiteSpace: 'nowrap',
            transition: '.15s',
          }}
        >
          详情
        </span>
      </div>

      {/* Agent IO 面板 */}
      {ioOpen && (
        <div style={{
          background: '#fafafa',
          borderRadius: 6,
          padding: '12px 14px',
          margin: '6px 0 10px',
          border: '1px solid #f0f0f0',
        }}>
          {agent.ioPrompt ? (
            <div style={{ marginBottom: 12 }}>
              <div style={{ fontSize: 11, fontWeight: 600, color: '#1677ff', marginBottom: 4 }}>
                INPUT — Agent 接收上下文
              </div>
              <div style={{
                background: '#f5f5f5',
                border: '1px solid #f0f0f0',
                borderRadius: 4,
                padding: '8px 10px',
                fontFamily: '"JetBrains Mono", monospace',
                fontSize: 11,
                lineHeight: 1.55,
                color: 'rgba(0,0,0,.88)',
                maxHeight: 200,
                overflowY: 'auto',
                whiteSpace: 'pre-wrap',
              }}>
                {agent.ioPrompt}
              </div>
            </div>
          ) : (
            <div style={{ fontSize: 11, color: 'rgba(0,0,0,.4)', marginBottom: 12 }}>
              （无 INPUT 记录）
            </div>
          )}
          {agent.ioResponse ? (
            <div>
              <div style={{ fontSize: 11, fontWeight: 600, color: '#52c41a', marginBottom: 4 }}>
                OUTPUT — Agent 处理结果
              </div>
              <div style={{
                background: '#f5f5f5',
                border: '1px solid #f0f0f0',
                borderRadius: 4,
                padding: '8px 10px',
                fontFamily: '"JetBrains Mono", monospace',
                fontSize: 11,
                lineHeight: 1.55,
                color: 'rgba(0,0,0,.88)',
                maxHeight: 200,
                overflowY: 'auto',
                whiteSpace: 'pre-wrap',
              }}>
                {agent.ioResponse}
              </div>
            </div>
          ) : (
            <div style={{ fontSize: 11, color: 'rgba(0,0,0,.4)' }}>
              （无 OUTPUT 记录）
            </div>
          )}
        </div>
      )}

      {/* Agent Body: LLM 调用行 */}
      {expanded && agent.llmCalls && agent.llmCalls.length > 0 && (
        <div style={{ paddingLeft: 32 }}>
          {agent.llmCalls.map((llm, i) => (
            <LLMCallRow key={i} llm={llm} />
          ))}
        </div>
      )}
    </div>
  );
}

/** LLM 调用行：图标 + 名称 + model + 耗时 + token + 详情按钮 */
function LLMCallRow({ llm }) {
  const [ioOpen, setIoOpen] = useState(false);

  return (
    <div style={{ marginBottom: 4 }}>
      <div style={{
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        padding: '8px 14px',
        background: '#fafafa',
        borderRadius: 4,
        borderLeft: '3px solid #722ed1',
        fontSize: 12,
      }}>
        <span>🧠</span>
        <span style={{ display: 'flex', flexDirection: 'column' }}>
          <span style={{ fontWeight: 600 }}>{llm.name || 'LLM'}</span>
          {llm.desc && (
            <span style={{ fontSize: 10, color: 'rgba(0,0,0,.5)', fontWeight: 400 }}>
              {llm.desc}
            </span>
          )}
        </span>
        <span style={{ fontSize: 11, color: 'rgba(0,0,0,.65)', fontFamily: '"JetBrains Mono", monospace' }}>
          {llm.model}
        </span>
        <span style={{
          marginLeft: 'auto',
          fontFamily: '"JetBrains Mono", monospace',
          fontSize: 11,
          color: '#722ed1',
          fontWeight: 600,
        }}>
          {llm.durationMs}ms
        </span>
        {llm.tokenInfo && (
          <span style={{ fontSize: 11, color: 'rgba(0,0,0,.65)', fontFamily: '"JetBrains Mono", monospace' }}>
            {llm.tokenInfo}
          </span>
        )}
        <span
          onClick={(e) => {
            e.stopPropagation();
            setIoOpen(!ioOpen);
          }}
          style={{
            display: 'inline-block',
            padding: '2px 8px',
            borderRadius: 4,
            fontSize: 10,
            fontWeight: 600,
            border: '1px solid #1677ff',
            color: '#1677ff',
            background: ioOpen ? '#e6f4ff' : '#fff',
            cursor: 'pointer',
            whiteSpace: 'nowrap',
            transition: '.15s',
          }}
        >
          详情
        </span>
      </div>

      {/* LLM IO 面板 */}
      {ioOpen && (
        <div style={{
          background: '#fafafa',
          borderRadius: 6,
          padding: '12px 14px',
          margin: '4px 0 10px',
          border: '1px solid #f0f0f0',
        }}>
          {llm.ioPrompt ? (
            <div style={{ marginBottom: 12 }}>
              <div style={{ fontSize: 11, fontWeight: 600, color: '#1677ff', marginBottom: 4 }}>
                Prompt
              </div>
              <div style={{
                background: '#f5f5f5',
                border: '1px solid #f0f0f0',
                borderRadius: 4,
                padding: '8px 10px',
                fontFamily: '"JetBrains Mono", monospace',
                fontSize: 11,
                lineHeight: 1.55,
                color: 'rgba(0,0,0,.88)',
                maxHeight: 200,
                overflowY: 'auto',
                whiteSpace: 'pre-wrap',
              }}>
                {llm.ioPrompt}
              </div>
            </div>
          ) : (
            <div style={{ fontSize: 11, color: 'rgba(0,0,0,.4)', marginBottom: 12 }}>
              （无 Prompt 记录）
            </div>
          )}
          {llm.ioResponse ? (
            <div>
              <div style={{ fontSize: 11, fontWeight: 600, color: '#52c41a', marginBottom: 4 }}>
                Response
              </div>
              <div style={{
                background: '#f5f5f5',
                border: '1px solid #f0f0f0',
                borderRadius: 4,
                padding: '8px 10px',
                fontFamily: '"JetBrains Mono", monospace',
                fontSize: 11,
                lineHeight: 1.55,
                color: 'rgba(0,0,0,.88)',
                maxHeight: 200,
                overflowY: 'auto',
                whiteSpace: 'pre-wrap',
              }}>
                {llm.ioResponse}
              </div>
            </div>
          ) : (
            <div style={{ fontSize: 11, color: 'rgba(0,0,0,.4)' }}>
              （无 Response 记录）
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * 将扁平 spanTree 重组为 Agent→LLM 嵌套结构
 *
 * 业务 span 按层级聚合为 Agent（遵循用户定义的路由架构）：
 * - L0 Agent（领域路由）：含 1 个 LLM，用于找到合适的 L1（银行业务领域）
 * - L1 Agent（业务路由）：含 2 个 LLM =
 *     · L1-LLM1 上下文分类（识别 FOLLOW-UP / SWITCH-NEW / RESUME）
 *     · L1-LLM2 意图改写 + 意图识别（选 L2，follow-up 时跳过）
 * - L2 Agent（业务执行）：内部含 1~N 个 LLM
 *
 * 特殊场景：
 * - follow-up：L1 块不含 L1-LLM2 → 标 FOLLOW-UP 徽标
 * - reRoute：由 TraceDetailModal 透传 reRouted 标志，顶部显示横幅
 */
function buildAgentTree(nodes) {
  if (!nodes || nodes.length === 0) return [];

  const allSpans = flattenSpans(nodes);
  const agents = [];
  let cur = null;

  for (const span of allSpans) {
    const opName = span.operationName || '';

    // 计算该 span 所属的 Agent 层级（L0/L1/L2）
    let agentLayer = null;
    if (opName.startsWith('L0:')) agentLayer = 'L0';
    else if (opName.startsWith('L1')) agentLayer = 'L1';
    else if (opName.startsWith('L2:')) agentLayer = 'L2';
    else continue; // 非业务 span（HTTP / POST CLIENT 等）跳过

    // 是否开启一个新的 Agent 分组（同一层级只合并到一个 Agent）
    const isNewAgent = !cur || cur.layer !== agentLayer;
    if (isNewAgent) {
      cur = createAgent(agentLayer, opName, span);
      agents.push(cur);
    }

    // 追加 LLM 调用（带角色说明）
    if (opName.startsWith('L0:')) {
      addLLMCall(cur, 'L0-LLM', opName, span, '领域路由 LLM — 选择 L1 业务领域');
    } else if (opName.startsWith('L1-LLM1')) {
      addLLMCall(cur, 'L1-LLM1', opName, span, '上下文分类 — 识别 FOLLOW-UP / SWITCH-NEW / RESUME');
      cur.hasLLM1 = true;
    } else if (opName.startsWith('L1-LLM2')) {
      addLLMCall(cur, 'L1-LLM2', opName, span, '意图改写 + 意图识别 — 选择 L2');
      cur.hasLLM2 = true;
    } else if (opName.startsWith('L1:') || opName.startsWith('L1-')) {
      addLLMCall(cur, 'L1-LLM', opName, span, 'L1 路由 LLM');
    } else if (opName.startsWith('L2:')) {
      addLLMCall(cur, 'L2-LLM', opName, span, '业务执行 LLM');
    }
  }

  // follow-up 判定：后端在「无活跃 agent」时短路 return SWITCH，跳过 L1-LLM1（非 L1-LLM2）。
  // 前端判定方向必须与后端一致：L1 Agent 若缺少 L1-LLM1，即为 follow-up / switch 短路场景。
  for (const a of agents) {
    if (a.layer === 'L1' && !a.hasLLM1) a.isFollowUp = true;
  }

  // 动态推导每个 Agent 的 LLM 构成描述（按真实子 span 列表，不再硬编码"含 2 个 LLM"）
  for (const a of agents) {
    a.desc = buildAgentDesc(a);
  }

  return agents;
}

/**
 * 按 Agent 的真实子 LLM 调用列表动态推导描述文本。
 * 修复 L1①：标题不再硬编码"含 2 个 LLM"，而是依据当前 span 子树真实包含的 LLM 调用数推导。
 */
function buildAgentDesc(agent) {
  const n = agent.llmCalls != null ? agent.llmCalls.length : 0;
  if (agent.layer === 'L0') {
    return n > 0 ? `含 ${n} 个 LLM，用于找到合适的 L1（银行业务领域）` : '领域路由 LLM — 选择 L1（银行业务领域）';
  }
  if (agent.layer === 'L1') {
    const parts = [];
    if (agent.hasLLM1) parts.push('L1-LLM1 上下文分类');
    if (agent.hasLLM2) parts.push('L1-LLM2 意图改写与识别（选 L2）');
    if (parts.length === 0) {
      return agent.isFollowUp ? '无 LLM 调用（后端短路 SWITCH，跳过 L1-LLM1）' : '无 LLM 调用';
    }
    return `含 ${n} 个 LLM：${parts.join(' + ')}`;
  }
  if (agent.layer === 'L2') {
    return `业务执行 · 内部含 ${n} 个 LLM`;
  }
  return agent.desc || '';
}

function createAgent(layer, opName, span) {
  let name, desc;
  if (layer === 'L0') {
    name = 'L0 · 领域路由';
    desc = '含 1 个 LLM，用于找到合适的 L1（银行业务领域）';
  } else if (layer === 'L1') {
    name = 'L1 · 业务路由';
    desc = '含 2 个 LLM：L1-LLM1 上下文分类 + L1-LLM2 意图改写与识别（选 L2）';
  } else if (layer === 'L2') {
    name = getL2AgentName(opName);
    desc = '业务执行 · 内部含 1~N 个 LLM';
  } else {
    name = opName;
    desc = '';
  }
  return {
    layer: layer,
    name: name,
    desc: desc,
    durationMs: span.durationMs || 0,
    statusCode: span.statusCode,
    ioPrompt: span.ioPrompt || '',
    ioResponse: span.ioResponse || '',
    attributes: span.attributes || {},
    llmCalls: [],
    totalDurationMs: 0,
    hasLLM1: false,
    hasLLM2: false,
    isFollowUp: false,
  };
}

function addLLMCall(agent, llmName, opName, span, desc) {
  const modelName = opName.includes(':') ? opName.split(':').slice(1).join(':') : 'unknown';
  // Find child POST CLIENT span for duration/token info
  let childDuration = span.durationMs || 0;
  if (span.children) {
    for (const child of span.children) {
      if (child.kind === 'CLIENT' && child.operationName === 'POST') {
        childDuration = child.durationMs || childDuration;
        break;
      }
    }
  }
  // Token 数：从 span 属性 ai.token.input / ai.token.output 读取（Core 注入）
  const attrs = span.attributes || {};
  const inT = attrs['ai.token.input'];
  const outT = attrs['ai.token.output'];
  let tokenInfo = '';
  if (inT != null || outT != null) {
    tokenInfo = `in:${inT != null ? inT : '?'} out:${outT != null ? outT : '?'}`;
  }
  agent.llmCalls.push({
    name: llmName,
    model: modelName,
    durationMs: childDuration,
    tokenInfo: tokenInfo,
    desc: desc,
    ioPrompt: span.ioPrompt || '',
    ioResponse: span.ioResponse || '',
  });
  agent.totalDurationMs += childDuration;
}

function getL2AgentName(opName) {
  const lower = (opName || '').toLowerCase();
  if (lower.includes('transfer')) return 'TransferService';
  if (lower.includes('wealth')) return 'WealthService';
  if (lower.includes('bill')) return 'BillService';
  if (lower.includes('chat')) return 'ChatService';
  return 'BusinessAgent';
}

/** 递归展平 span tree */
function flattenSpans(nodes) {
  const result = [];
  for (const node of nodes) {
    result.push(node);
    if (node.children) {
      result.push(...flattenSpans(node.children));
    }
  }
  return result;
}

/** 层级检测 */
function detectLayer(node) {
  const op = (node.operationName || node.agentName || '').toLowerCase();
  if (op.startsWith('l0:') || op.includes('domainrouter')) return 'L0';
  if (op.startsWith('l1-') || op.includes('l1')) return 'L1';
  if (op.startsWith('l2:') || op.includes('l2') || op.includes('service') || op.includes('graph')) return 'L2';
  return node.agentType || 'L0';
}

/** Agent 显示名称 */
function getAgentName(opName, layer) {
  if (layer === 'L0') return 'DomainRouter';
  if (layer === 'L1') {
    if (opName.includes('LLM1')) return 'ContextRouter';
    if (opName.includes('LLM2')) return 'SubGraphRouter';
    return 'L1 Router';
  }
  if (layer === 'L2') {
    if (opName.toLowerCase().includes('transfer')) return 'TransferService';
    if (opName.toLowerCase().includes('wealth')) return 'WealthService';
    if (opName.toLowerCase().includes('bill')) return 'BillService';
    return 'BusinessAgent';
  }
  return opName;
}

/** Agent 描述 */
function getAgentDesc(opName, layer, span) {
  if (layer === 'L0') return 'L1领域路由';
  if (layer === 'L1') {
    if (opName.includes('LLM1')) return '会话分类 · FOLLOW/SWITCH/RESUME';
    if (opName.includes('LLM2')) return '意图重写 + L2选择';
    return 'L1路由';
  }
  if (layer === 'L2') return '业务执行';
  return '';
}

/** LLM 显示名称 */
function getLLMName(opName, layer) {
  if (layer === 'L0') return 'L0-LLM';
  if (layer === 'L1') {
    if (opName.includes('LLM1')) return 'L1-LLM1';
    if (opName.includes('LLM2')) return 'L1-LLM2';
    return 'L1-LLM';
  }
  if (layer === 'L2') return 'L2-LLM';
  return 'LLM';
}

/** Token 信息字符串 */
function buildTokenInfo(span) {
  const attrs = span.attributes || {};
  const input = attrs['ai.token.input'];
  const output = attrs['ai.token.output'];
  if (input || output) {
    return 'in:' + (input || '?') + ' out:' + (output || '?');
  }
  return '';
}

/** 层级颜色 */
function getLayerColor(layer) {
  const colors = {
    L0: { bg: '#e6f4ff', text: '#1677ff', tagBg: '#e6f4ff', tagText: '#1677ff', dot: '#1677ff' },
    L1: { bg: '#f9f0ff', text: '#722ed1', tagBg: '#f9f0ff', tagText: '#722ed1', dot: '#722ed1' },
    L2: { bg: '#f6ffed', text: '#52c41a', tagBg: '#f6ffed', tagText: '#52c41a', dot: '#52c41a' },
  };
  return colors[layer] || colors.L0;
}

export default SpanTree;
