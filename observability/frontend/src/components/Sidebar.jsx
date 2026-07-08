import React from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Layout, Menu } from 'antd';
import {
  DashboardOutlined,
  MessageOutlined,
  ApartmentOutlined,
  BulbOutlined,
  FileTextOutlined,
  AlertOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import LiveIndicator from './LiveIndicator';
import { useAppContext } from '../context/AppContext';

const { Sider } = Layout;

/**
 * 导航项分组定义
 *
 * 监控组：总览 / 会话 / 链路 / AI洞察 / 日志
 * 管理组：告警规则 / 系统设置
 */
function getMenuItems(alertCount) {
  return [
    {
      type: 'group',
      label: '监控',
      key: 'group-monitor',
      children: [
        {
          key: '/dashboard',
          icon: <DashboardOutlined />,
          label: '总览大屏',
        },
        {
          key: '/session',
          icon: <MessageOutlined />,
          label: '会话回放',
        },
        {
          key: '/trace',
          icon: <ApartmentOutlined />,
          label: '链路追踪',
        },
        {
          key: '/insight',
          icon: <BulbOutlined />,
          label: 'AI 洞察',
        },
        {
          key: '/logs',
          icon: <FileTextOutlined />,
          label: '日志查询',
        },
      ],
    },
    {
      type: 'group',
      label: '管理',
      key: 'group-admin',
      children: [
        {
          key: '/alerts',
          icon: <AlertOutlined />,
          label: alertCount > 0 ? `告警规则 (${alertCount})` : '告警规则',
        },
        {
          key: '/settings',
          icon: <SettingOutlined />,
          label: '系统设置',
        },
      ],
    },
  ];
}

/**
 * 侧边导航栏 — 映射 dashboard-v15 的 sidebar 设计
 * - 品牌区：Logo + 系统名
 * - 导航菜单：8个导航项，分两个分组，使用 react-router-dom NavLink 模式
 * - 底部状态：LIVE 脉冲指示器 + 版本信息
 */
function Sidebar() {
  const location = useLocation();
  const navigate = useNavigate();
  const { state } = useAppContext();

  // 提取一级路径作为 selectedKey
  const selectedKey = '/' + location.pathname.split('/')[1];

  const handleClick = ({ key }) => {
    navigate(key);
  };

  return (
    <Sider
      width={220}
      style={{
        background: '#001529',
        borderRight: 'none',
        display: 'flex',
        flexDirection: 'column',
        position: 'fixed',
        top: 0,
        left: 0,
        bottom: 0,
        zIndex: 100,
        overflow: 'auto',
      }}
    >
      {/* 品牌区 */}
      <div
        style={{
          height: 56,
          display: 'flex',
          alignItems: 'center',
          padding: '0 20px',
          color: '#fff',
          fontSize: 15,
          fontWeight: 600,
          gap: 10,
          borderBottom: '1px solid rgba(255,255,255,.08)',
        }}
      >
        <div
          style={{
            width: 28,
            height: 28,
            background: '#1677ff',
            borderRadius: 6,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontSize: 13,
          }}
        >
          BA
        </div>
        <div>
          <div style={{ fontWeight: 600, fontSize: 14, color: '#e8edf4', lineHeight: 1.2 }}>
            Bank AI
          </div>
          <div style={{ fontSize: 10, color: 'rgba(255,255,255,.45)', fontFamily: "'JetBrains Mono', monospace" }}>
            observability
          </div>
        </div>
      </div>

      {/* 导航菜单 */}
      <Menu
        mode="inline"
        theme="dark"
        selectedKeys={[selectedKey]}
        onClick={handleClick}
        items={getMenuItems(state.alertCount)}
        style={{
          flex: 1,
          background: 'transparent',
          borderRight: 'none',
          padding: '8px 0',
        }}
      />

      {/* 底部状态 */}
      <div
        style={{
          padding: '12px 20px',
          borderTop: '1px solid rgba(255,255,255,.08)',
          color: 'rgba(255,255,255,.65)',
          fontSize: 11,
          display: 'flex',
          alignItems: 'center',
          gap: 6,
        }}
      >
        <LiveIndicator />
        <span style={{ fontFamily: "'JetBrains Mono', monospace" }}>
          mobile-ai-demo · v0.1
        </span>
      </div>
    </Sider>
  );
}

export default Sidebar;
