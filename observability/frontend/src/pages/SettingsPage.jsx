import React, { useState, useEffect, useCallback } from 'react';
import { Card, Table, Empty, Spin, Typography, Tag } from 'antd';
import { SettingOutlined } from '@ant-design/icons';
import { fetchSettings } from '../api/client';

const { Title, Paragraph } = Typography;

/**
 * SettingsPage — 系统设置页
 *
 * 双栏布局:
 * - 左: 采集配置（OTel Collector 状态/采样率/Tag策略/Prompt存储）
 * - 右: 存储后端（Metrics/Trace/Logs/业务画像）
 *
 * 调用 /api/v1/settings 获取数据
 */
function SettingsPage() {
  const [loading, setLoading] = useState(true);
  const [settings, setSettings] = useState(null);

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const data = await fetchSettings();
      setSettings(data);
    } catch (err) {
      console.error('Failed to load settings:', err);
      // Keep settings as null — render empty state
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const collection = settings?.collection || {};
  const storage = settings?.storage || {};

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>
          <SettingOutlined style={{ marginRight: 8 }} />
          系统设置
        </Title>
        <Paragraph type="secondary" style={{ marginBottom: 0, marginTop: 4 }}>
          管理采集配置、存储设置和系统参数
        </Paragraph>
      </div>

      <Spin spinning={loading}>
        {!loading && !settings && (
          <Empty description="暂无设置数据" image={Empty.PRESENTED_IMAGE_SIMPLE} style={{ marginBottom: 16 }} />
        )}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
          {/* 左: 采集配置 */}
          <Card
            title="采集配置"
            extra={<span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>Spring AI Alibaba</span>}
          >
            <Table
              dataSource={[
                { key: 'collector', label: 'OTel Collector', value: collection.collectorStatus || '—' },
                { key: 'sampling', label: 'Trace 采样率', value: collection.samplingRate || '—' },
                { key: 'tag', label: 'Metric Tag 策略', value: collection.tagStrategy || '—' },
                { key: 'prompt', label: 'Prompt 存储', value: collection.promptStorage || '—' },
              ]}
              columns={SETTINGS_COLUMNS}
              pagination={false}
              showHeader={false}
              size="small"
            />
          </Card>

          {/* 右: 存储后端 */}
          <Card title="存储后端">
            <Table
              dataSource={[
                { key: 'metrics', label: 'Metrics', value: storage.metrics || '—' },
                { key: 'trace', label: 'Trace', value: storage.trace || '—' },
                { key: 'logs', label: 'Logs', value: storage.logs || '—' },
                { key: 'profile', label: '业务画像', value: storage.profile || '—' },
              ]}
              columns={SETTINGS_COLUMNS}
              pagination={false}
              showHeader={false}
              size="small"
            />
          </Card>
        </div>
      </Spin>
    </div>
  );
}

const SETTINGS_COLUMNS = [
  {
    title: '配置项',
    dataIndex: 'label',
    key: 'label',
    width: 140,
    render: (t) => <span style={{ fontWeight: 500, fontSize: 13 }}>{t}</span>,
  },
  {
    title: '值',
    dataIndex: 'value',
    key: 'value',
    render: (v) => {
      // OTel Collector 状态特殊处理
      if (v === 'online' || v === 'Online') {
        return (
          <span style={{
            display: 'inline-flex', alignItems: 'center', gap: 4,
            padding: '2px 8px', borderRadius: 4, fontSize: 12, fontWeight: 500,
            background: '#f6ffed', color: '#52c41a', border: '1px solid #b7eb8f',
          }}>
            <span style={{ width: 6, height: 6, borderRadius: '50%', display: 'inline-block', background: '#52c41a' }} />
            online
          </span>
        );
      }
      if (v === 'offline') {
        return <Tag color="error">offline</Tag>;
      }
      return <span style={{ fontSize: 13 }}>{v}</span>;
    },
  },
];

export default SettingsPage;
