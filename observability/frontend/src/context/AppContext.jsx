import React, { createContext, useContext, useReducer } from 'react';

const AppContext = createContext();

const initialState = {
  /** 全局时间范围: 30m / 1h / 6h / 24h / 7d */
  timeRange: '6h',
  /** 未处理告警数量（Sidebar badge 计数） */
  alertCount: 0,
  /** Collector 在线状态 */
  collectorOnline: true,
};

function appReducer(state, action) {
  switch (action.type) {
    case 'SET_TIME_RANGE':
      return { ...state, timeRange: action.payload };
    case 'SET_ALERT_COUNT':
      return { ...state, alertCount: action.payload };
    case 'SET_COLLECTOR_STATUS':
      return { ...state, collectorOnline: action.payload };
    default:
      return state;
  }
}

/**
 * AppProvider — 全局状态容器
 *
 * 覆盖：时间范围 / 告警计数 / Collector 状态
 * 各页面独立管理自身数据状态（本地 useState + useCallback）
 */
export function AppProvider({ children }) {
  const [state, dispatch] = useReducer(appReducer, initialState);
  return (
    <AppContext.Provider value={{ state, dispatch }}>
      {children}
    </AppContext.Provider>
  );
}

/**
 * useAppContext — 获取全局状态
 */
export function useAppContext() {
  const context = useContext(AppContext);
  if (!context) {
    throw new Error('useAppContext must be used within an AppProvider');
  }
  return context;
}

export default AppContext;
