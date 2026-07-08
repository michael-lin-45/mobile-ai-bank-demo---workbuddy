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
function SpanTree({ nodes, level = 0, onIOOpen }) {
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

        {/* 详情按钮 */}
        {(agent.ioPrompt || agent.ioResponse) && (
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
        )}
      </div>

      {/* Agent IO 面板 */}
      {ioOpen && (agent.ioPrompt || agent.ioResponse) && (
        <div style={{
          background: '#fafafa',
          borderRadius: 6,
          padding: '12px 14px',
          margin: '6px 0 10px',
          border: '1px solid #f0f0f0',
        }}>
          {agent.ioPrompt && (
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
          )}
          {agent.ioResponse && (
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
        <span style={{ fontWeight: 600 }}>{llm.name || 'LLM'}</span>
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
        {(llm.ioPrompt || llm.ioResponse) && (
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
        )}
      </div>

      {/* LLM IO 面板 */}
      {ioOpen && (llm.ioPrompt || llm.ioResponse) && (
        <div style={{
          background: '#fafafa',
          borderRadius: 6,
          padding: '12px 14px',
          margin: '4px 0 10px',
          border: '1px solid #f0f0f0',
        }}>
          {llm.ioPrompt && (
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
          )}
          {llm.ioResponse && (
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
          )}
        </div>
      )}
    </div>
  );
}

/**
 * 将扁平 spanTree 重组为 Agent→LLM 嵌套结构
 * 业务 span（L0:/L1-LLM1:/L1-LLM2:/L2: 前缀）识别为 Agent
 * 其子 HTTP span（POST to LLM API）识别为 LLM 调用
 */
function buildAgentTree(nodes) {
  if (!nodes || nodes.length === 0) return [];

  const allSpans = flattenSpans(nodes);

  // Group business spans into agents:
  // - L0: spans with opName starting "L0:" -> one L0 Agent with 1 LLM call
  // - L1: spans with opName starting "L1:" or "L1-" -> one L1 Agent with 1-2 LLM calls (L1-LLM1, L1-LLM2)
  // - L2: spans with opName starting "L2:" -> one L2 Agent with 1+ LLM calls
  const agents = [];
  let l0Agent = null;
  let l1Agent = null;
  let l2Agent = null;

  for (const span of allSpans) {
    const opName = span.operationName || '';
    const layer = detectLayer(span);

    if (opName.startsWith('L0:')) {
      // L0 Agent - create if not exists, or add as LLM call
      if (!l0Agent) {
        l0Agent = createAgent('L0', 'DomainRouter', 'L1领域路由', span);
        agents.push(l0Agent);
      }
      addLLMCall(l0Agent, 'L0-LLM', opName, span);
    } else if (opName.startsWith('L1-LLM1')) {
      // L1-LLM1 is an LLM call within the L1 Agent
      if (!l1Agent) {
        l1Agent = createAgent('L1', 'ContextRouter', '会话分类 + L2选择', span);
        agents.push(l1Agent);
      }
      addLLMCall(l1Agent, 'L1-LLM1', opName, span);
    } else if (opName.startsWith('L1-LLM2')) {
      // L1-LLM2 is an LLM call within the L1 Agent
      if (!l1Agent) {
        l1Agent = createAgent('L1', 'ContextRouter', '会话分类 + L2选择', span);
        agents.push(l1Agent);
      }
      addLLMCall(l1Agent, 'L1-LLM2', opName, span);
    } else if (opName.startsWith('L1:') || opName.startsWith('L1-')) {
      // Generic L1 span (not LLM1 or LLM2)
      if (!l1Agent) {
        l1Agent = createAgent('L1', 'L1 Router', 'L1路由', span);
        agents.push(l1Agent);
      }
      addLLMCall(l1Agent, 'L1-LLM', opName, span);
    } else if (opName.startsWith('L2:')) {
      // L2 Agent - may have multiple LLM calls
      if (!l2Agent) {
        l2Agent = createAgent('L2', getL2AgentName(opName), '业务执行', span);
        agents.push(l2Agent);
      }
      addLLMCall(l2Agent, 'L2-LLM', opName, span);
    }
  }

  return agents;
}

function createAgent(layer, name, desc, span) {
  return {
    layer: layer,
    name: name,
    desc: desc,
    durationMs: span.durationMs || 0,
    statusCode: span.statusCode,
    ioPrompt: span.ioPrompt || '',
    ioResponse: span.ioResponse || '',
    llmCalls: [],
    totalDurationMs: 0,
  };
}

function addLLMCall(agent, llmName, opName, span) {
  const modelName = opName.includes(':') ? opName.split(':').slice(1).join(':') : 'unknown';
  // Find child POST CLIENT span for duration/token info
  let childDuration = span.durationMs || 0;
  let tokenInfo = '';
  if (span.children) {
    for (const child of span.children) {
      if (child.kind === 'CLIENT' && child.operationName === 'POST') {
        childDuration = child.durationMs || childDuration;
        const attrs = child.attributes || {};
        break;
      }
    }
  }
  agent.llmCalls.push({
    name: llmName,
    model: modelName,
    durationMs: childDuration,
    tokenInfo: tokenInfo,
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
