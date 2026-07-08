import React, { useState, useCallback, useEffect, useRef } from 'react';
import { fetchLogs } from '../api/client';

/**
 * 日志查询页 — 表格 + 级别筛选 + 全文搜索 + TraceID/UserID/SessionID 输入
 *
 * 日志条目格式：
 * [LEVEL]  时间戳  [模块] 消息  trace跳转链接
 *
 * 级别配色：INFO(蓝badge) / WARN(黄badge) / ERROR(红badge)
 */
const LEVEL_CONFIG = {
  INFO: { color: '#1677ff', bg: '#e6f4ff', border: '#91caff' },
  WARN: { color: '#faad14', bg: '#fffbe6', border: '#ffe58f' },
  ERROR: { color: '#ff4d4f', bg: '#fff2f0', border: '#ffccc7' },
  DEBUG: { color: '#8c8c8c', bg: '#f5f5f5', border: '#d9d9d9' },
};

function LogViewerPage() {
  const [logs, setLogs] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // 筛选
  const [level, setLevel] = useState('');
  const [searchQ, setSearchQ] = useState('');
  const [searchTraceId, setSearchTraceId] = useState('');
  const [searchUserId, setSearchUserId] = useState('');
  const [searchSessionId, setSearchSessionId] = useState('');

  // 分页
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(50);
  const [totalElements, setTotalElements] = useState(0);

  // 用 ref 获取最新状态，避免 useCallback stale closure
  const stateRef = useRef({ level, searchQ, searchTraceId, searchUserId, searchSessionId, page, pageSize });
  stateRef.current = { level, searchQ, searchTraceId, searchUserId, searchSessionId, page, pageSize };

  const loadLogs = useCallback(async (overrideParams = {}) => {
    const s = { ...stateRef.current, ...overrideParams };
    setLoading(true);
    setError(null);
    try {
      const result = await fetchLogs({
        level: s.level || undefined,
        q: s.searchQ || undefined,
        traceId: s.searchTraceId || undefined,
        userId: s.searchUserId || undefined,
        sessionId: s.searchSessionId || undefined,
        page: s.page,
        size: s.pageSize,
      });
      if (result) {
        setLogs(result.content || []);
        setTotalElements(result.totalElements || 0);
      } else {
        setLogs([]);
        setTotalElements(0);
      }
    } catch (err) {
      setError(err.message);
      setLogs([]);
      setTotalElements(0);
    } finally {
      setLoading(false);
    }
  }, []); // 空依赖，通过 ref 绕过 stale closure

  // 页面首次挂载时自动加载
  useEffect(() => {
    loadLogs();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const handleTraceClick = (traceId) => {
    if (traceId) {
      window.location.href = `/trace`;
    }
  };

  const handleSearch = () => {
    setPage(0);
    loadLogs({ page: 0 });
  };

  const handleLevelChange = (lvl) => {
    setLevel(lvl);
    setPage(0);
    loadLogs({ level: lvl, page: 0 });
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      {error && (
        <div style={{
          padding: 10,
          background: '#fff2f0',
          border: '1px solid #ffccc7',
          borderRadius: 6,
          color: '#ff4d4f',
          fontSize: 12,
        }}>
          ⚠ 后端数据加载失败，请稍后重试 — {error}
        </div>
      )}

      {/* 筛选栏 */}
      <div style={{
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        flexWrap: 'wrap',
      }}>
        {/* 级别筛选 pills */}
        <span style={{ fontSize: 11, color: 'rgba(0,0,0,.65)', fontWeight: 600 }}>级别:</span>
        {['', 'INFO', 'WARN', 'ERROR'].map((lvl) => (
          <div
            key={lvl || 'ALL'}
            onClick={() => handleLevelChange(lvl)}
            style={{
              padding: '4px 10px',
              borderRadius: 16,
              fontSize: 11,
              fontWeight: 500,
              cursor: 'pointer',
              background: level === lvl ? '#e6f4ff' : '#f5f5f5',
              color: level === lvl ? '#1677ff' : 'rgba(0,0,0,.65)',
              border: `1px solid ${level === lvl ? '#91caff' : '#d9d9d9'}`,
              transition: 'all 180ms',
            }}
          >
            {lvl || '全部'}
          </div>
        ))}

        <span style={{ width: 1, height: 20, background: '#d9d9d9', margin: '0 4px' }} />

        {/* 输入框 */}
        <input
          placeholder="user.id"
          value={searchUserId}
          onChange={(e) => setSearchUserId(e.target.value)}
          style={inputStyle}
          onFocus={focusStyle}
          onBlur={blurStyle}
        />
        <input
          placeholder="session.id"
          value={searchSessionId}
          onChange={(e) => setSearchSessionId(e.target.value)}
          style={inputStyle}
          onFocus={focusStyle}
          onBlur={blurStyle}
        />
        <input
          placeholder="trace.id"
          value={searchTraceId}
          onChange={(e) => setSearchTraceId(e.target.value)}
          style={{
            ...inputStyle,
            fontFamily: '"JetBrains Mono", "SF Mono", Consolas, monospace',
            fontSize: 12,
          }}
          onFocus={focusStyle}
          onBlur={blurStyle}
        />
        <input
          placeholder="全文搜索"
          value={searchQ}
          onChange={(e) => setSearchQ(e.target.value)}
          style={{ ...inputStyle, width: 200 }}
          onFocus={focusStyle}
          onBlur={blurStyle}
        />
        <button onClick={handleSearch} style={btnStyle}>查询</button>

        <span style={{ marginLeft: 'auto', fontSize: 12, color: 'rgba(0,0,0,.45)' }}>
          共 {totalElements} 条
        </span>
      </div>

      {/* 日志列表 */}
      <div style={{
        background: '#fff',
        borderRadius: 8,
        boxShadow: '0 1px 2px rgba(0,0,0,.03)',
        overflow: 'hidden',
      }}>
        {loading && (
          <div style={{ padding: 24, textAlign: 'center', color: 'rgba(0,0,0,.45)' }}>
            加载中...
          </div>
        )}
        {!loading && logs.length === 0 && (
          <div style={{ padding: 24, textAlign: 'center', color: 'rgba(0,0,0,.45)' }}>
            暂无日志数据
          </div>
        )}
        {!loading && logs.map((log, idx) => {
          const lvlConfig = LEVEL_CONFIG[log.level] || LEVEL_CONFIG.INFO;
          return (
            <div
              key={log.id || idx}
              style={{
                padding: '8px 12px',
                borderBottom: '1px solid #f0f0f0',
                fontFamily: '"JetBrains Mono", "SF Mono", Consolas, monospace',
                fontSize: 12,
                lineHeight: 1.6,
                display: 'flex',
                gap: 12,
                alignItems: 'flex-start',
              }}
              onMouseEnter={(e) => { e.currentTarget.style.background = '#fafafa'; }}
              onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
            >
              {/* 级别 Badge */}
              <span
                style={{
                  width: 44,
                  flexShrink: 0,
                  fontWeight: 600,
                  display: 'inline-block',
                  textAlign: 'center',
                  padding: '1px 0',
                  borderRadius: 3,
                  fontSize: 11,
                  background: lvlConfig.bg,
                  color: lvlConfig.color,
                  border: `1px solid ${lvlConfig.border}`,
                }}
              >
                {log.level || 'INFO'}
              </span>

              {/* 时间戳 */}
              <span style={{ color: 'rgba(0,0,0,.45)', whiteSpace: 'nowrap' }}>
                {log.timestamp || '-'}
              </span>

              {/* 消息内容 */}
              <span style={{ flex: 1, wordBreak: 'break-all' }}>
                [{log.module || log.serviceName || '-'}] {log.message || ''}
                {log.traceId && (
                  <span
                    onClick={() => handleTraceClick(log.traceId)}
                    style={{
                      color: 'rgba(0,0,0,.45)',
                      cursor: 'pointer',
                      marginLeft: 8,
                    }}
                    onMouseEnter={(e) => { e.currentTarget.style.color = '#1677ff'; e.currentTarget.style.textDecoration = 'underline'; }}
                    onMouseLeave={(e) => { e.currentTarget.style.color = 'rgba(0,0,0,.45)'; e.currentTarget.style.textDecoration = 'none'; }}
                  >
                    trace {(log.traceId.length > 8 ? log.traceId.substring(0, 8) + '…' : log.traceId)} ↗
                  </span>
                )}
              </span>
            </div>
          );
        })}
      </div>

      {/* 分页 */}
      {totalElements > pageSize && (
        <div style={{ display: 'flex', justifyContent: 'center', gap: 8 }}>
          <button
            onClick={() => { const p = Math.max(0, page - 1); setPage(p); loadLogs({ page: p }); }}
            disabled={page === 0}
            style={{ ...btnStyle, background: page === 0 ? '#d9d9d9' : '#1677ff' }}
          >
            上一页
          </button>
          <span style={{ padding: '6px 12px', fontSize: 13, color: 'rgba(0,0,0,.65)' }}>
            {page + 1} / {Math.ceil(totalElements / pageSize)}
          </span>
          <button
            onClick={() => { const p = page + 1; setPage(p); loadLogs({ page: p }); }}
            disabled={(page + 1) * pageSize >= totalElements}
            style={{ ...btnStyle, background: (page + 1) * pageSize >= totalElements ? '#d9d9d9' : '#1677ff' }}
          >
            下一页
          </button>
        </div>
      )}
    </div>
  );
}

const inputStyle = {
  padding: '6px 12px',
  border: '1px solid #d9d9d9',
  borderRadius: 6,
  fontSize: 13,
  width: 160,
  outline: 'none',
  boxSizing: 'border-box',
};

const btnStyle = {
  padding: '6px 16px',
  background: '#1677ff',
  color: '#fff',
  border: 'none',
  borderRadius: 6,
  fontSize: 13,
  cursor: 'pointer',
};

function focusStyle(e) { e.target.style.borderColor = '#1677ff'; e.target.style.boxShadow = '0 0 0 2px rgba(22,119,255,.1)'; }
function blurStyle(e) { e.target.style.borderColor = '#d9d9d9'; e.target.style.boxShadow = 'none'; }

export default LogViewerPage;
