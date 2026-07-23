import React, { useState, useEffect, useCallback } from 'react';
import { Card, Table, Empty, Spin, Statistic, Space } from 'antd';
import ApiErrorAlert from '../components/ApiErrorAlert';
import { fetchInvocations } from '../api/client';

/**
 * 工具函数调用子视图（V23 B4 / 任务分解 M10）。
 *
 * 作为「外部调用」TAB 的「工具函数」分段内容。展示业务系统工具函数（crm / risk / ots / doc）
 * 的聚合指标与明细。不再内嵌图表，数据来自 GET /invocations?category=tool。
 */
function ToolCallTab() {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [rows, setRows] = useState([]);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchInvocations('tool');
      setRows(data || []);
    } catch (err) {
      console.error('[ToolCallTab] load failed:', err);
      setError(err.message || '工具函数调用数据加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadData(); }, [loadData]);

  const totalCalls = rows.reduce((a, r) => a + (r.calls || 0), 0);
  const totalSuccess = rows.reduce((a, r) => a + (r.success || 0), 0);
  const totalFailed = rows.reduce((a, r) => a + (r.failed || 0), 0);
  const successRate = totalCalls > 0 ? ((totalSuccess / totalCalls) * 100).toFixed(1) : '0.0';
  const p95 = rows.length ? Math.max(...rows.map((r) => r.p95LatencyMs || 0)) : 0;
  const errorRate = totalCalls > 0 ? ((totalFailed / totalCalls) * 100).toFixed(2) : '0.00';

  return (
    <Card size="small" title="工具函数调用">
      <ApiErrorAlert error={error} onRetry={loadData} />
      <Spin spinning={loading}>
        <Space wrap style={{ marginBottom: 12 }}>
          <Statistic title="总调用" value={totalCalls} />
          <Statistic title="成功率" value={successRate} suffix="%" valueStyle={{ color: '#52c41a' }} />
          <Statistic title="P95 耗时" value={p95} suffix="ms" valueStyle={{ color: '#faad14' }} />
          <Statistic title="错误率" value={errorRate} suffix="%" valueStyle={{ color: '#cf1322' }} />
        </Space>
        {rows.length === 0 && !loading ? (
          <Empty description="暂无工具函数调用" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <Table
            dataSource={rows}
            rowKey="name"
            pagination={false}
            size="small"
            columns={[
              { title: '工具名称', dataIndex: 'name', render: (t) => <span style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 12 }}>{t}</span> },
              { title: '调用次数', dataIndex: 'calls', align: 'center' },
              { title: '成功', dataIndex: 'success', align: 'center', render: (v) => <span style={{ color: '#52c41a' }}>{v}</span> },
              { title: '失败', dataIndex: 'failed', align: 'center', render: (v) => <span style={{ color: v > 0 ? '#ff4d4f' : 'inherit' }}>{v}</span> },
              { title: '平均耗时', dataIndex: 'avgLatencyMs', align: 'center' },
              { title: 'P95 耗时', dataIndex: 'p95LatencyMs', align: 'center', render: (v) => <span style={{ color: '#faad14' }}>{v}</span> },
              {
                title: '错误率',
                dataIndex: 'errorRate',
                align: 'center',
                render: (v) => <span style={{ color: v > 5 ? '#ff4d4f' : v > 0 ? '#faad14' : '#52c41a', fontFamily: '"JetBrains Mono", monospace', fontSize: 12 }}>{v}%</span>,
              },
              { title: '典型错误', dataIndex: 'lastError', render: (t) => <span style={{ color: 'rgba(0,0,0,.45)' }}>{t || '—'}</span> },
            ]}
          />
        )}
      </Spin>
    </Card>
  );
}

export default ToolCallTab;
