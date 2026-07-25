import React from 'react';
import { Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { Layout, Typography, Select, Button, Tag, Alert } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import Sidebar from './Sidebar';
import HealthBadge from './HealthBadge';
import DashboardPage from '../pages/DashboardPage';
import SessionViewerPage from '../pages/SessionViewerPage';
import TraceExplorerPage from '../pages/TraceExplorerPage';
import AIInsightsPage from '../pages/AIInsightsPage';
import LogViewerPage from '../pages/LogViewerPage';
import AlertRulesPage from '../pages/AlertRulesPage';
import SettingsPage from '../pages/SettingsPage';
import SessionReplay from './SessionReplay';
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
 * RouteErrorBoundary — 路由级错误边界（Task B：止血整页白屏）。
 *
 * 背景：此前 <Routes> 外层无错误边界，任一非总览路由在渲染期抛错会向上冒泡、
 *       卸载整棵组件树 → 整页白屏（侧边栏/顶栏一并消失，导航"点不动"）。
 *
 * 该边界只包裹 <Routes>：
 *   - 捕获到错误时，仅内容区显示 antd <Alert type="error"> + 重试，
 *     侧边栏/顶栏/导航保持可用（导航到其它路由即恢复）。
 *   - key={location.pathname}：切换路由时边界以新 key 重挂载，
 *     hasError 复位，避免"卡在错误态"无法跳出。
 *   - 重试：复位错误态并（通过 onRetry 提升 key）强制重挂载当前路由，重新渲染。
 */
class RouteErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error) {
    return { hasError: true, error };
  }

  componentDidCatch(error, errorInfo) {
    // 记录错误（含组件栈），便于浏览器 console 排错
    console.error('[RouteErrorBoundary] 路由渲染异常：', error, errorInfo);
  }

  handleRetry = () => {
    this.setState({ hasError: false, error: null });
    if (typeof this.props.onRetry === 'function') {
      this.props.onRetry();
    }
  };

  render() {
    if (this.state.hasError) {
      return (
        <Alert
          type="error"
          showIcon
          style={{ borderRadius: 8 }}
          message="页面渲染失败"
          description={
            <span>
              {this.state.error?.message || '未知错误'}
              <div style={{ marginTop: 8, fontSize: 12, color: 'rgba(0,0,0,.45)' }}>
                错误已记录到浏览器控制台（console），可贴出具体报错以便最终定位根因；
                也可点击右上角「重试」，或切换左侧其它导航恢复正常。
              </div>
            </span>
          }
          action={
            <Button size="small" danger onClick={this.handleRetry}>
              重试
            </Button>
          }
        />
      );
    }
    return this.props.children;
  }
}

/**
 * AppLayout — 全局布局组件
 *
 * 结构：
 * - Sidebar（暗色，220px，固定定位）
 * - 右侧：Topbar + Content（React Router <Routes>）
 */
function AppLayout() {
  const { state, dispatch } = useAppContext();
  const location = useLocation();

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
            <HealthBadge />
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
          <RouteErrorBoundary key={location.pathname} onRetry={() => { /* key 由 location 变化驱动重挂载 */ }}>
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
          </RouteErrorBoundary>
        </Content>
      </Layout>

      {/* 全局会话回放（响应 openSessionReplay / session:replay） */}
      <SessionReplay />
    </Layout>
  );
}

export default AppLayout;
