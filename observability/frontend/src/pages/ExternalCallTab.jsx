import React, { useState, useEffect, useCallback } from 'react';
import { Card, Segmented, Table, Tag, Empty, Spin, Statistic, Space, Typography } from 'antd';
import { fetchInvocations } from '../api/client';
import ApiErrorAlert from '../components/ApiErrorAlert';
import RagPanel from '../components/RagPanel';
import ToolCallTab from './ToolCallTab';

/**
 * 外部调用 TAB（V23 B4 / 任务分解 M10）。
 *
 * 以 Segmented 在「全部 / RAG / 工具函数 / SKILL / MCP」间切换：
 * - 全部：四类调用聚合总览（KPI + 全量明细表）
 * - RAG：RagPanel
 * - 工具函数：ToolCallTab（重构后的工具函数子视图）
 * - SKILL：暂未开放
 * - MCP：MCP 工具调用明细（B5 接入真实埋点）
 */
const { Text } = Typography;

const CATEGORY_COLOR = { rag: 'purple', tool: 'blue', skill: 'cyan', mcp: 'geekblue' };

function ExternalCallTab() {
  const [seg, setSeg] = useState('all');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [all, setAll] = useState([]);
  const [mcp, setMcp] = useState([]);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [a, m] = await Promise.all([
        fetchInvocations('all'),
        fetchInvocations('mcp'),
      ]);
      setAll(a || []);
      setMcp(m || []);
    } catch (err) {
      console.error('[ExternalCallTab] load failed:', err);
      setError(err.message || '外部调用数据加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadData(); }, [loadData]);

  const totalCalls = all.reduce((a, r) => a + (r.calls || 0), 0);
  const totalFailed = all.reduce((a, r) => a + (r.failed || 0), 0);
  const errorRate = totalCalls > 0 ? ((totalFailed / totalCalls) * 100).toFixed(2) : '0.00';

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

      <Spin spinning={loading && (seg === 'all' || seg === 'mcp')}>
        {seg === 'all' && (
          <Card size="small" title="外部调用总览">
            <Space wrap style={{ marginBottom: 12 }}>
              <Statistic title="总调用" value={totalCalls} />
              <Statistic title="失败" value={totalFailed} />
              <Statistic title="整体错误率" value={errorRate} suffix="%" valueStyle={{ color: '#cf1322' }} />
              <Statistic title="调用类别" value={new Set(all.map((r) => r.category)).size} suffix="类" />
            </Space>
            <Table
              dataSource={all}
              rowKey={(r) => r.category + ':' + r.name}
              pagination={false}
              size="small"
              columns={[
                { title: '类别', dataIndex: 'category', width: 100, render: (c) => <Tag color={CATEGORY_COLOR[c] || 'default'}>{c}</Tag> },
                { title: '调用', dataIndex: 'name', render: (v) => <Text code>{v}</Text> },
                { title: '调用', dataIndex: 'calls', align: 'right' },
                { title: '成功', dataIndex: 'success', align: 'right', render: (v) => <span style={{ color: '#52c41a' }}>{v}</span> },
                { title: '失败', dataIndex: 'failed', align: 'right', render: (v) => <span style={{ color: v > 0 ? '#ff4d4f' : 'inherit' }}>{v}</span> },
                { title: '平均(ms)', dataIndex: 'avgLatencyMs', align: 'right' },
                { title: 'P95(ms)', dataIndex: 'p95LatencyMs', align: 'right' },
                { title: '错误率', dataIndex: 'errorRate', align: 'right', render: (v) => <Tag color={v > 5 ? 'red' : 'green'}>{v}%</Tag> },
              ]}
            />
          </Card>
        )}

        {seg === 'rag' && <RagPanel />}

        {seg === 'tool' && <ToolCallTab />}

        {seg === 'skill' && (
          <Card size="small">
            <Empty description="SKILL 调用统计暂未开放" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          </Card>
        )}

        {seg === 'mcp' && (
          <Card size="small" title="MCP 工具调用">
            {mcp.length === 0 ? (
              <Empty description="暂无 MCP 调用" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              <Table
                dataSource={mcp}
                rowKey="name"
                pagination={false}
                size="small"
                columns={[
                  { title: '工具', dataIndex: 'name', render: (v) => <Text code>{v}</Text> },
                  { title: '调用', dataIndex: 'calls', align: 'right' },
                  { title: '成功', dataIndex: 'success', align: 'right', render: (v) => <span style={{ color: '#52c41a' }}>{v}</span> },
                  { title: '失败', dataIndex: 'failed', align: 'right', render: (v) => <span style={{ color: v > 0 ? '#ff4d4f' : 'inherit' }}>{v}</span> },
                  { title: '平均(ms)', dataIndex: 'avgLatencyMs', align: 'right' },
                  { title: 'P95(ms)', dataIndex: 'p95LatencyMs', align: 'right' },
                  { title: '错误率', dataIndex: 'errorRate', align: 'right', render: (v) => <Tag color={v > 5 ? 'red' : 'green'}>{v}%</Tag> },
                  { title: '典型错误', dataIndex: 'lastError', render: (t) => <span style={{ color: 'rgba(0,0,0,.45)' }}>{t || '—'}</span> },
                ]}
              />
            )}
          </Card>
        )}
      </Spin>
    </div>
  );
}

export default ExternalCallTab;
