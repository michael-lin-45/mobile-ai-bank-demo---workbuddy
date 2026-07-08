import React from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import { Layout, Typography, Select, Button, Tag } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import Sidebar from './Sidebar';
import DashboardPage from '../pages/DashboardPage';
import SessionViewerPage from '../pages/SessionViewerPage';
import TraceExplorerPage from '../pages/TraceExplorerPage';
import AIInsightsPage from '../pages/AIInsightsPage';
import LogViewerPage from '../pages/LogViewerPage';
import AlertRulesPage from '../pages/AlertRulesPage';
import SettingsPage from '../pages/SettingsPage';
import { useAppContext } from '../context/AppContext';

const { Content } = Layout;
const { Title } = Typography;

const TIME_OPTIONS = [
  { value: '30m', label: '最近30分钟' },
  { value: '1h', label: '最近1小时' },
  { value: '6h', label: '最近6小时' },
  { value: '24h', label: '最近24小时' },
  { value: '7d', label: '最近7天' },
];

/**
 * AppLayout — 全局布局组件
 *
 * 结构：
 * - Sidebar（暗色，220px，固定定位）
 * - 右侧：Topbar + Content（React Router <Routes>）
 */
function AppLayout() {
  const { state, dispatch } = useAppContext();

  const handleTimeChange = (value) => {
    dispatch({ type: 'SET_TIME_RANGE', payload: value });
  };

  const handleRefresh = () => {
    window.dispatchEvent(new CustomEvent('global-refresh'));
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      {/* 暗色侧边栏 */}
      <Sidebar />

      {/* 主内容区 */}
      <Layout style={{ marginLeft: 220 }}>
        {/* 顶部栏 */}
        <div
          style={{
            height: 56,
            background: '#fff',
            borderBottom: '1px solid #f0f0f0',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '0 24px',
            position: 'sticky',
            top: 0,
            zIndex: 50,
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <Title level={5} style={{ margin: 0 }}>
              总览大屏
            </Title>
            <Tag color="success" style={{ margin: 0 }}>
              DEV
            </Tag>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <Select
              value={state.timeRange}
              onChange={handleTimeChange}
              options={TIME_OPTIONS}
              style={{ width: 150 }}
              size="small"
              popupMatchSelectWidth={false}
            />
            <Button
              icon={<ReloadOutlined />}
              size="small"
              onClick={handleRefresh}
            />
          </div>
        </div>

        {/* 页面内容 */}
        <Content
          style={{
            padding: '20px 24px',
            background: '#f0f2f5',
            minHeight: 'calc(100vh - 56px)',
            overflowX: 'hidden',
          }}
        >
          <Routes>
            <Route path="/" element={<Navigate to="/dashboard" replace />} />
            <Route path="/dashboard" element={<DashboardPage />} />
            <Route path="/session" element={<SessionViewerPage />} />
            <Route path="/trace" element={<TraceExplorerPage />} />
            <Route path="/insight" element={<AIInsightsPage />} />
            <Route path="/logs" element={<LogViewerPage />} />
            <Route path="/alerts" element={<AlertRulesPage />} />
            <Route path="/settings" element={<SettingsPage />} />
          </Routes>
        </Content>
      </Layout>
    </Layout>
  );
}

export default AppLayout;
