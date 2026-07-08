import React, { useState, useEffect } from 'react';
import { Layout, Card, Statistic, Table, Tag, Typography, Space, Tabs } from 'antd';
import { ArrowUpOutlined, WarningOutlined, CheckCircleOutlined, ClockCircleOutlined } from '@ant-design/icons';

const { Header, Content } = Layout;
const { Title } = Typography;

function App() {
  const [stats, setStats] = useState({
    totalRequests: 0,
    successRate: 100,
    avgResponseTime: 0,
    errorCount: 0,
    aiCallCount: 0,
    recentRequests: []
  });

  // 每隔3秒自动刷新数据
  useEffect(() => {
    const fetchData = async () => {
      try {
        const res = await fetch('http://localhost:9090/api/v1/dashboard/stats');
        const data = await res.json();
        setStats(data);
      } catch (e) {
        console.error('拉取数据失败:', e);
      }
    };
    fetchData();
    const timer = setInterval(fetchData, 3000);
    return () => clearInterval(timer);
  }, []);

  const logColumns = [
    { title: '时间', dataIndex: 'time', key: 'time', width: 180 },
    { title: '请求方法', dataIndex: 'method', key: 'method', width: 80 },
    { title: '状态码', dataIndex: 'status', key: 'status', width: 80,
      render: (s) => <Tag color={s.startsWith('2') ? 'green' : 'red'}>{s}</Tag> },
    { title: '请求路径', dataIndex: 'uri', key: 'uri' },
    { title: '耗时', dataIndex: 'duration', key: 'duration', width: 100,
      render: (v) => <span>{(v * 1000).toFixed(2)} ms</span> }
  ];

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header style={{ background: '#001529', padding: '0 24px' }}>
        <Title level={3} style={{ color: 'white', margin: 0, lineHeight: '64px' }}>
          银行AI智能体可观测平台
        </Title>
      </Header>
      <Content style={{ padding: '24px', maxWidth: '1600px', margin: '0 auto' }}>
        {/* 核心指标卡片 */}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '16px', marginBottom: '24px' }}>
          <Card>
            <Statistic
              title="总请求数"
              value={stats.totalRequests}
              prefix={<ArrowUpOutlined style={{ color: '#3f8600' }} />}
              valueStyle={{ color: '#3f8600' }}
            />
          </Card>
          <Card>
            <Statistic
              title="请求成功率"
              value={stats.successRate}
              suffix="%"
              prefix={<CheckCircleOutlined style={{ color: '#3f8600' }} />}
              valueStyle={{ color: stats.successRate >= 95 ? '#3f8600' : '#faad14' }}
            />
          </Card>
          <Card>
            <Statistic
              title="平均响应时间"
              value={stats.avgResponseTime}
              suffix="ms"
              prefix={<ClockCircleOutlined style={{ color: '#1890ff' }} />}
              valueStyle={{ color: stats.avgResponseTime < 1000 ? '#3f8600' : '#faad14' }}
            />
          </Card>
          <Card>
            <Statistic
              title="异常请求数"
              value={stats.errorCount}
              prefix={<WarningOutlined style={{ color: '#cf1322' }} />}
              valueStyle={{ color: '#cf1322' }}
            />
          </Card>
        </div>

        <Card>
          <Tabs defaultActiveKey="logs">
            <Tabs.TabPane tab="请求日志" key="logs">
              <Table
                dataSource={stats.recentRequests}
                columns={logColumns}
                rowKey="time"
                pagination={false}
                bordered={false}
              />
            </Tabs.TabPane>
            <Tabs.TabPane tab="大模型调用" key="ai">
              <Title level={4} style={{ marginBottom: '16px' }}>大模型调用总次数：{stats.aiCallCount}</Title>
              <p>后续会补充大模型耗时、token消耗、错误率等指标</p>
            </Tabs.TabPane>
            <Tabs.TabPane tab="链路追踪" key="trace">
              <p>后续会补充全链路追踪展示功能</p>
            </Tabs.TabPane>
          </Tabs>
        </Card>
      </Content>
    </Layout>
  );
}

export default App;
