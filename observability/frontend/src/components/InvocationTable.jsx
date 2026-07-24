import React from 'react';
import { Table, Tag, Empty, Typography } from 'antd';
import { toInvocationRows } from '../services/insightAdapters';

const { Text } = Typography;

const CATEGORY_COLOR = { rag: 'purple', tool: 'blue', skill: 'cyan', mcp: 'geekblue' };

/**
 * InvocationTable — 通用外部调用表格
 *
 * 被「全部 / RAG / 工具函数 / SKILL / MCP」多段复用，对齐 MCP 表列：
 *   工具 / 调用 / 成功 / 失败 / 平均 / P95 / 错误率 / 典型错误
 *
 * 内部经 toInvocationRows 归一（兼容后端 InvocationRecord VO 字段 callCount 等）。
 *
 * Props:
 * - dataSource: 原始调用记录数组（InvocationRecordMock 或 VO）
 * - showCategory: 是否显示「类别」列（默认 true；SKILL/MCP 单段可传 false）
 * - demo: mock 回退角标
 */
function InvocationTable({ dataSource = [], showCategory = true, demo = false }) {
  const rows = toInvocationRows(dataSource);

  if (rows.length === 0) {
    return <Empty description="暂无调用数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }

  const columns = [
    ...(showCategory
      ? [{
          title: '类别', dataIndex: 'category', width: 100,
          render: (c) => <Tag color={CATEGORY_COLOR[c] || 'default'}>{c}</Tag>,
        }]
      : []),
    { title: '工具', dataIndex: 'name', render: (v) => <Text code>{v}</Text> },
    { title: '调用', dataIndex: 'calls', key: 'calls', align: 'right' },
    { title: '成功', dataIndex: 'success', key: 'success', align: 'right', render: (v) => <span style={{ color: '#52c41a' }}>{v}</span> },
    {
      title: '失败', dataIndex: 'failed', key: 'failed', align: 'right',
      render: (v) => <span style={{ color: v > 0 ? '#ff4d4f' : 'inherit' }}>{v}</span>,
    },
    { title: '平均(ms)', dataIndex: 'avgLatencyMs', key: 'avgLatencyMs', align: 'right' },
    { title: 'P95(ms)', dataIndex: 'p95LatencyMs', key: 'p95LatencyMs', align: 'right' },
    {
      title: '错误率', dataIndex: 'errorRate', key: 'errorRate', align: 'right',
      render: (v) => <Tag color={v > 5 ? 'red' : 'green'}>{v}%</Tag>,
    },
    {
      title: '典型错误', dataIndex: 'typicalError',
      render: (t) => <span style={{ color: 'rgba(0,0,0,.45)' }}>{t || '—'}</span>,
    },
  ];

  return (
    <Table
      dataSource={rows}
      rowKey={(r) => `${r.category || ''}:${r.name}`}
      pagination={false}
      size="small"
      columns={columns}
    />
  );
}

export default InvocationTable;
