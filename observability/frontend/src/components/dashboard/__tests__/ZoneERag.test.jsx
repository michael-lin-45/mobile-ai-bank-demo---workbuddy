import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import ZoneERag from '../ZoneERag';

/**
 * ZoneERag 测试（V23 Zone E — 知识检索 RAG 三卡）。
 *
 * 覆盖：
 *  ① metrics=null → 不渲染
 *  ② metrics={}（无 rag 字段）→ 兜底值 45/64/0.82 + 单位 ms/%/分 + 待接入角标
 *  ③ metrics 提供真实 rag 字段 → 显示真实值而非兜底
 *  ④ 三卡 borderTop 含 teal 主题色 #08979c
 *
 * 注：因使用 @vitejs/plugin-react 的自动 JSX runtime，仅 import React 不影响；无需 Router 等 Provider。
 */
describe('ZoneERag (V23 Zone E — 知识检索 RAG)', () => {
  it('① metrics=null 时不渲染（容器为空且查不到「知识检索」文本）', () => {
    const { container } = render(<ZoneERag metrics={null} />);
    expect(container).toBeEmptyDOMElement();
    expect(screen.queryByText(/知识检索/)).not.toBeInTheDocument();
  });

  it('② metrics={} 无 rag 字段时渲染 3 张卡，显示兜底值 45/64/0.82 与单位 ms/%/分，并含「待 Core RAG 链路接入」', () => {
    render(<ZoneERag metrics={{}} />);

    // 主数值（直接文本节点，避免与单位 span 拼接）
    expect(screen.getByText('45', { exact: true })).toBeInTheDocument();
    expect(screen.getByText('64', { exact: true })).toBeInTheDocument();
    expect(screen.getByText('0.82', { exact: true })).toBeInTheDocument();

    // 单位
    expect(screen.getByText('ms', { exact: true })).toBeInTheDocument();
    expect(screen.getByText('%', { exact: true })).toBeInTheDocument();
    expect(screen.getByText('分', { exact: true })).toBeInTheDocument();

    // 区头「待接入」角标 + 区标题
    expect(screen.getByText(/待 Core RAG 链路接入/)).toBeInTheDocument();
    expect(screen.getByText(/知识检索/)).toBeInTheDocument();
  });

  it('③ metrics 提供真实 rag 字段时显示真实值 120/70/0.9 而非兜底值', () => {
    render(
      <ZoneERag
        metrics={{ ragRetrievalP95: 120, ragHitRateTopK: 70, ragTopKRelevance: 0.9 }}
      />,
    );

    expect(screen.getByText('120', { exact: true })).toBeInTheDocument();
    expect(screen.getByText('70', { exact: true })).toBeInTheDocument();
    expect(screen.getByText('0.9', { exact: true })).toBeInTheDocument();

    // 兜底值不应出现
    expect(screen.queryByText('45', { exact: true })).not.toBeInTheDocument();
    expect(screen.queryByText('64', { exact: true })).not.toBeInTheDocument();
    expect(screen.queryByText('0.82', { exact: true })).not.toBeInTheDocument();
  });

  it('④ 三张卡片样式 borderTop 含 teal 主题色 #08979c（兼容 jsdom 颜色归一化）', () => {
    const { container } = render(<ZoneERag metrics={{}} />);
    const cardNodes = Array.from(container.querySelectorAll('div')).filter((el) => {
      const bt = (el.style.borderTop || '').toLowerCase();
      return bt.includes('3px solid') && /#08979c|rgb\(8,\s*151,\s*156\)/.test(bt);
    });

    expect(cardNodes).toHaveLength(3);
    expect(cardNodes[0].style.borderTop).toMatch(/#08979c|rgb\(8,\s*151,\s*156\)/);
  });
});
