import React, { useState, useEffect, useCallback } from 'react';
import { Card, Segmented, Spin, Empty } from 'antd';
import { fetchInvocations } from '../api/client';
import ApiErrorAlert from '../components/ApiErrorAlert';
import InsightKpiCard, { DemoBadge } from '../components/InsightKpiCard';
import InvocationTable from '../components/InvocationTable';
import RagPanel from '../components/RagPanel';
import ToolCallTab from './ToolCallTab';
import { isEmpty } from '../services/insightAdapters';
import { getSkillInvocationsMock, getMcpInvocationsMock } from '../services/mockInsights';

/**
 * 外部调用 TAB（改造版）。
 *
 * 以 Segmented 在「全部 / RAG / 工具函数 / SKILL / MCP」间切换：
 * - 全部：四类调用聚合总览（共享 KPI 卡 + 全量明细表）
 * - RAG：RagPanel
 * - 工具函数：ToolCallTab
 * - SKILL：渲染 InvocationTable + skillInvocationsMock（修复"暂未开放"空态）
 * - MCP：MCP 工具调用明细（接 mock 兜底）
 *
 * 顶部 KPI 统一为 InsightKpiCard（docs/system_design.md §1.3）。
 */
function ExternalCallTab() {
  const [seg, setSeg] = useState('all');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [all, setAll] = useState([]);
  const [allDemo, setAllDemo] = useState(false);
  const [mcp, setMcp] = useState([]);
  const [mcpDemo, setMcpDemo] = useState(false);

  // SKILL 段 mock 直给（无需 fetch），始终有数据
  const skill = getSkillInvocationsMock();

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [a, m] = await Promise.all([
        fetchInvocations('all').catch(() => null),
        fetchInvocations('mcp').catch(() => null),
      ]);
      const aEmpty = isEmpty(a);
      const mEmpty = isEmpty(m);
      setAll(aEmpty ? getMcpInvocationsMock().concat(getSkillInvocationsMock()) : (a || []));
      setAllDemo(aEmpty);
      setMcp(mEmpty ? getMcpInvocationsMock() : (m || []));
      setMcpDemo(mEmpty);
    } catch (err) {
      console.error('[ExternalCallTab] load failed:', err);
      setError(err.message || '外部调用数据加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadData(); }, [loadData]);

  const summary = (() => {
    if (seg === 'skill') {
      const totalCalls = skill.reduce((x, r) => x + r.calls, 0);
      const totalFailed = skill.reduce((x, r) => x + r.failed, 0);
      const totalSuccess = skill.reduce((x, r) => x + r.success, 0);
      return {
        totalCalls,
        totalFailed,
        errorRate: totalCalls > 0 ? Number(((totalFailed / totalCalls) * 100).toFixed(2)) : 0,
        successRate: totalCalls > 0 ? Number(((totalSuccess / totalCalls) * 100).toFixed(2)) : 0,
        demo: false,
      };
    }
    if (seg === 'mcp') {
      const totalCalls = mcp.reduce((x, r) => x + r.calls, 0);
      const totalFailed = mcp.reduce((x, r) => x + r.failed, 0);
      const totalSuccess = mcp.reduce((x, r) => x + r.success, 0);
      return {
        totalCalls,
        totalFailed,
        errorRate: totalCalls > 0 ? Number(((totalFailed / totalCalls) * 100).toFixed(2)) : 0,
        successRate: totalCalls > 0 ? Number(((totalSuccess / totalCalls) * 100).toFixed(2)) : 0,
        demo: mcpDemo,
      };
    }
    // 全部：优先聚合真实，否则用 summary mock
    const totalCalls = all.reduce((x, r) => x + (r.calls || 0), 0);
    const totalFailed = all.reduce((x, r) => x + (r.failed || 0), 0);
    const totalSuccess = all.reduce((x, r) => x + (r.success || 0), 0);
    return {
      totalCalls,
      totalFailed,
      errorRate: totalCalls > 0 ? Number(((totalFailed / totalCalls) * 100).toFixed(2)) : 0,
      successRate: totalCalls > 0 ? Number(((totalSuccess / totalCalls) * 100).toFixed(2)) : 0,
      demo: allDemo,
    };
  })();

  const renderKpis = (s, demo) => (
    <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 12 }}>
      <InsightKpiCard label="总调用" value={s.totalCalls} color="#1677ff" bg="#f0f5ff" demo={demo} />
      <InsightKpiCard label="成功" value={s.totalSuccess != null ? s.totalSuccess : (s.totalCalls - s.totalFailed)} color="#52c41a" bg="#f6ffed" demo={demo} />
      <InsightKpiCard label="失败" value={s.totalFailed} color="#ff4d4f" bg="#fff2f0" demo={demo} />
      <InsightKpiCard label="整体错误率" value={s.errorRate} unit="%" color="#fa8c16" bg="#fff7e6" demo={demo} />
    </div>
  );

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <ApiErrorAlert error={error} onRetry={loadData} />

      <Segmented
        block
        value={seg}
        onChange={setSeg}
        options={[
          { label: '全部', value: 'all' },
          { label: 'RAG', value: 'rag' },
          { label: '工具函数', value: 'tool' },
          { label: 'SKILL', value: 'skill' },
          { label: 'MCP', value: 'mcp' },
        ]}
      />

      <Spin spinning={loading && (seg === 'all' || seg === 'mcp' || seg === 'skill')}>
        {seg === 'all' && (
          <Card size="small" title="外部调用总览" extra={allDemo ? <DemoBadge /> : null}>
            {renderKpis(summary, allDemo)}
            <InvocationTable dataSource={all} showCategory />
          </Card>
        )}

        {seg === 'rag' && <RagPanel />}

        {seg === 'tool' && <ToolCallTab />}

        {seg === 'skill' && (
          <Card size="small" title="SKILL 调用统计" extra={<DemoBadge />}>
            {skill.length === 0 ? (
              <Empty description="暂无 SKILL 调用" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              <>
                {renderKpis(summary, false)}
                <InvocationTable dataSource={skill} showCategory={false} />
              </>
            )}
          </Card>
        )}

        {seg === 'mcp' && (
          <Card size="small" title="MCP 工具调用" extra={mcpDemo ? <DemoBadge /> : null}>
            {mcp.length === 0 ? (
              <Empty description="暂无 MCP 调用" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              <>
                {renderKpis(summary, mcpDemo)}
                <InvocationTable dataSource={mcp} showCategory={false} />
              </>
            )}
          </Card>
        )}
      </Spin>
    </div>
  );
}

export default ExternalCallTab;
