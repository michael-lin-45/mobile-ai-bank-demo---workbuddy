import React, { useState, useEffect, useCallback } from 'react';
import { Card, Table, Tag, Empty, Spin, Statistic, Space } from 'antd';
import { fetchInvocations } from '../api/client';
import ApiErrorAlert from './ApiErrorAlert';

/**
 * RAG 调用面板（V23 B4 / 任务分解 M10）。
 *
 * 普通卡片样式（非特殊背景），展示 RAG 检索类外部调用的聚合指标与明细。
 * 数据来源：GET /invocations?category=rag。
 */
function RagPanel() {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [rows, setRows] = useState([]);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchInvocations('rag');
      setRows(data || []);
    } catch (err) {
      console.error('[RagPanel] load failed:', err);
      setError(err.message || 'RAG 调用数据加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadData(); }, [loadData]);

  const totalCalls = rows.reduce((a, r) => a + (r.calls || 0), 0);
  const avgLatency = rows.length
    ? Math.round(rows.reduce((a, r) => a + (r.avgLatencyMs || 0), 0) / rows.length)
    : 0;
  const p95 = rows.length
    ? Math.max(...rows.map((r) => r.p95LatencyMs || 0))
    : 0;
  const totalFailed = rows.reduce((a, r) => a + (r.failed || 0), 0);
  const errorRate = totalCalls > 0 ? ((totalFailed / totalCalls) * 100).toFixed(2) : '0.00';

  return (
    <Card size="small" title="RAG 检索调用" style={{ background: '#fff' }}>
      <ApiErrorAlert error={error} onRetry={loadData} />
      <Spin spinning={loading}>
        <Space wrap style={{ marginBottom: 12 }}>
          <Statistic title="调用次数" value={totalCalls} />
          <Statistic title="平均耗时" value={avgLatency} suffix="ms" />
          <Statistic title="P95 耗时" value={p95} suffix="ms" />
          <Statistic title="错误率" value={errorRate} suffix="%" valueStyle={{ color: '#cf1322' }} />
        </Space>
        {rows.length === 0 && !loading ? (
          <Empty description="暂无 RAG 调用" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <Table
            dataSource={rows}
            rowKey="name"
            pagination={false}
            size="small"
            columns={[
              { title: '检索器', dataIndex: 'name', render: (v) => <Text code>{v}</Text> },
              { title: '调用', dataIndex: 'calls', align: 'right' },
              { title: '成功', dataIndex: 'success', align: 'right', render: (v) => <span style={{ color: '#52c41a' }}>{v}</span> },
              { title: '失败', dataIndex: 'failed', align: 'right', render: (v) => <span style={{ color: v > 0 ? '#ff4d4f' : 'inherit' }}>{v}</span> },
              { title: '平均(ms)', dataIndex: 'avgLatencyMs', align: 'right' },
              { title: 'P95(ms)', dataIndex: 'p95LatencyMs', align: 'right' },
              { title: '错误率', dataIndex: 'errorRate', align: 'right', render: (v) => <Tag color={v > 5 ? 'red' : 'green'}>{v}%</Tag> },
            ]}
          />
        )}
      </Spin>
    </Card>
  );
}

function Text({ children, code, ...rest }) {
  if (code) return <span style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 12 }} {...rest}>{children}</span>;
  return <span {...rest}>{children}</span>;
}

export default RagPanel;
