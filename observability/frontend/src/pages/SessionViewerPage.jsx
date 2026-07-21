import React, { useState, useCallback, useEffect } from 'react';
import SessionFilter from '../components/SessionFilter';
import SessionTable from '../components/SessionTable';
import SessionDetailModal from '../components/SessionDetailModal';
import { fetchSessions, fetchSessionDetail } from '../api/client';
import { formatBeijingTime } from '../utils/time';

/**
 * 会话回放页 — 筛选栏 + 列表 + 全屏 Modal
 *
 * 筛选：Session ID / User ID / 渠道 / 意图 / 智能体 / 状态
 * 列表：Session ID · User ID · 渠道 · 时间 · 时长 · 轮次 · 意图 · 执行智能体 · Token · 会话状态 · 操作
 * Modal：头部 + 对话轮次 + 会话总结 + 满意度入口
 */
function SessionViewerPage() {
  const [filters, setFilters] = useState({
    sessionId: '',
    userId: '',
    channel: '',
    intent: '',
    agent: '',
    status: '',
  });
  const [sessions, setSessions] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // Pagination state
  const [page, setPage] = useState(0);
  const [pageSize] = useState(10);
  const [totalElements, setTotalElements] = useState(0);

  // Modal state
  const [modalVisible, setModalVisible] = useState(false);
  const [selectedSession, setSelectedSession] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const handleFilterChange = useCallback((name, value) => {
    setFilters(prev => ({ ...prev, [name]: value }));
  }, []);

  const handleSearch = useCallback(async (pageNum = 0) => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchSessions({ ...filters, page: pageNum, size: pageSize });
      if (data) {
        if (Array.isArray(data)) {
          setSessions(data);
          setTotalElements(data.length);
        } else {
          setSessions(data.content || []);
          setTotalElements(data.totalElements || 0);
        }
      } else {
        setSessions([]);
        setTotalElements(0);
      }
      setPage(pageNum);
    } catch (err) {
      setError(err.message);
      setSessions([]);
      setTotalElements(0);
    } finally {
      setLoading(false);
    }
  }, [filters, pageSize]);

  // Auto-load sessions on mount
  useEffect(() => {
    handleSearch();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleViewSession = useCallback(async (session) => {
    setModalVisible(true);
    setDetailLoading(true);
    // Immediately transform to avoid crash on missing summary/turns
    setSelectedSession(transformSessionDetail(session));

    try {
      const detail = await fetchSessionDetail(session.sessionId);
      if (detail) {
        setSelectedSession(transformSessionDetail(detail));
      }
    } catch (err) {
      // Keep the row-level transform as fallback; turns may be empty
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const handleCloseModal = useCallback(() => {
    setModalVisible(false);
    setSelectedSession(null);
  }, []);

  const handleTraceClick = useCallback((traceId) => {
    if (traceId) {
      window.location.href = `/trace?traceId=${encodeURIComponent(traceId)}`;
    }
  }, []);

  const handleSatisfaction = useCallback((rating) => {
    console.log('[Satisfaction]', { sessionId: selectedSession?.sessionId, rating });
    // T04 对接：submitSatisfaction(sessionId, rating)
  }, [selectedSession]);

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
          ⚠ 后端数据加载失败，请检查后端服务 — {error}
        </div>
      )}

      {/* 筛选栏 */}
      <SessionFilter
        filters={filters}
        onChange={handleFilterChange}
        onSearch={handleSearch}
      />

      {/* 会话列表 */}
      <div style={{
        background: '#fff',
        borderRadius: 8,
        boxShadow: '0 1px 2px rgba(0,0,0,.03)',
        overflow: 'hidden',
      }}>
        <div style={{
          padding: '14px 20px',
          borderBottom: '1px solid #f0f0f0',
          fontSize: 13,
          fontWeight: 600,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
        }}>
          <span>会话列表</span>
          <span style={{ fontSize: 11, color: 'rgba(0,0,0,.45)', fontWeight: 400 }}>
            {sessions.length} 条
          </span>
        </div>
        <SessionTable
          sessions={sessions}
          onViewSession={handleViewSession}
          loading={loading}
        />
        {totalElements > pageSize && (
          <div style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'flex-end',
            gap: 12,
            padding: '12px 20px',
            borderTop: '1px solid #f0f0f0',
            fontSize: 13,
          }}>
            <span style={{ color: 'rgba(0,0,0,.45)' }}>
              {page * pageSize + 1}-{Math.min((page + 1) * pageSize, totalElements)} / {totalElements}
            </span>
            <button
              onClick={() => handleSearch(page - 1)}
              disabled={page === 0}
              style={page === 0 ? paginationBtnDisabled : paginationBtn}
            >
              上一页
            </button>
            <span style={{ fontWeight: 500 }}>
              {page + 1} / {Math.ceil(totalElements / pageSize) || 1}
            </span>
            <button
              onClick={() => handleSearch(page + 1)}
              disabled={(page + 1) * pageSize >= totalElements}
              style={(page + 1) * pageSize >= totalElements ? paginationBtnDisabled : paginationBtn}
            >
              下一页
            </button>
          </div>
        )}
      </div>

      {/* 会话回放 Modal */}
      <SessionDetailModal
        session={selectedSession}
        visible={modalVisible}
        onClose={handleCloseModal}
        onTraceClick={handleTraceClick}
        onSatisfaction={handleSatisfaction}
        satisfactionDisabled={false}
      />
    </div>
  );
}

/** 转换后端数据为前端格式 — 只做数据映射，不做假数据填充 */
function transformSessionDetail(data) {
  if (!data) return null;

  // If turns already exist, return as-is
  if (data.turns && data.turns.length > 0) return data;

  // If timeline exists, convert timeline → turns
  if (data.timeline && data.timeline.length > 0) {
    return {
      ...data,
      turns: data.timeline.map((t, i) => ({
        turnIndex: i + 1,
        time: formatBeijingTime(t.timestamp) || '-',
        intent: t.intent || '-',
        agentPath: t.agentPath || '-',
        confidence: t.confidence || '-',
        duration: t.duration ? `${t.duration}ms` : '-',
        status: t.status || null,
        userMessage: t.query || '',
        aiMessage: t.answer || '',
        tokens: t.tokens || 0,
        model: t.model || '-',
        params: t.params || null,
        traceId: t.traceId || null,
      })),
      durationText: data.durationText || formatDuration(data.duration),
      timeRange: data.timeRange || '-',
      domainSwitches: data.domainSwitches || 0,
      tokens: data.tokens || 0,
      summary: {
        intentFlow: data.intentFlow || '-',
        domainSwitches: data.domainSwitches || 0,
        totalDuration: data.durationText || formatDuration(data.duration) || '-',
        totalTokens: data.tokens || 0,
      },
    };
  }

  // No turns and no timeline — keep turns empty, let Modal show empty state
  return {
    ...data,
    turns: [],
    durationText: data.durationText || formatDuration(data.duration) || '-',
    timeRange: data.timeRange || '-',
    domainSwitches: data.domainSwitches || 0,
    tokens: data.tokens || 0,
    summary: {
      intentFlow: data.intentFlow || '-',
      domainSwitches: data.domainSwitches || 0,
      totalDuration: data.durationText || formatDuration(data.duration) || '-',
      totalTokens: data.tokens || 0,
    },
  };
}

function formatDuration(ms) {
  if (!ms) return '-';
  if (ms < 1000) return `${ms}ms`;
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  const rs = s % 60;
  return `${m}m ${rs}s`;
}

const paginationBtn = {
  padding: '4px 12px',
  border: '1px solid #d9d9d9',
  borderRadius: 6,
  background: '#fff',
  color: 'rgba(0,0,0,.65)',
  fontSize: 13,
  cursor: 'pointer',
};

const paginationBtnDisabled = {
  padding: '4px 12px',
  border: '1px solid #d9d9d9',
  borderRadius: 6,
  background: '#f5f5f5',
  color: 'rgba(0,0,0,.25)',
  fontSize: 13,
  cursor: 'not-allowed',
};

export default SessionViewerPage;
