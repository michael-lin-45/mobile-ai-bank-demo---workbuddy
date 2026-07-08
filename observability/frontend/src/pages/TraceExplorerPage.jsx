import React, { useState, useCallback, useEffect } from 'react';
import TraceTable from '../components/TraceTable';
import TraceDetailModal from '../components/TraceDetailModal';
import { fetchTraces, fetchTraceDetail } from '../api/client';

/**
 * 链路追踪页 — 列表 + 全屏 Modal
 *
 * 列表：Trace ID · Session ID · User ID · 意图 · Agents · 耗时 · TTFT · Token · 状态 · 查看详情
 * Modal：IO卡片 + Agent链路树 + Span瀑布图
 */
function TraceExplorerPage() {
  const [filters, setFilters] = useState({
    userId: '',
    sessionId: '',
    traceId: '',
    intent: '',
    agent: '',
  });
  const [traces, setTraces] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // Pagination state
  const [page, setPage] = useState(0);
  const [pageSize] = useState(10);
  const [totalElements, setTotalElements] = useState(0);

  // Modal state
  const [modalVisible, setModalVisible] = useState(false);
  const [selectedTrace, setSelectedTrace] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const handleSearch = useCallback(async (pageNum = 0) => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchTraces({ ...filters, page: pageNum, size: pageSize });
      if (data) {
        if (Array.isArray(data)) {
          setTraces(data);
          setTotalElements(data.length);
        } else {
          setTraces(data.content || []);
          setTotalElements(data.totalElements || 0);
        }
      } else {
        setTraces([]);
        setTotalElements(0);
      }
      setPage(pageNum);
    } catch (err) {
      setError(err.message);
      setTraces([]);
      setTotalElements(0);
    } finally {
      setLoading(false);
    }
  }, [filters, pageSize]);

  // Auto-load traces on mount
  useEffect(() => {
    handleSearch();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleViewDetail = useCallback(async (trace) => {
    setModalVisible(true);
    setDetailLoading(true);
    setSelectedTrace(trace);

    try {
      const detail = await fetchTraceDetail(trace.traceId);
      if (detail) {
        setSelectedTrace(detail);
      }
    } catch (err) {
      // Keep the row-level trace data as fallback
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const handleCloseModal = useCallback(() => {
    setModalVisible(false);
    setSelectedTrace(null);
  }, []);

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
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <input
          placeholder="user.id"
          value={filters.userId}
          onChange={(e) => setFilters(prev => ({ ...prev, userId: e.target.value }))}
          style={inputStyle}
          onFocus={focusStyle}
          onBlur={blurStyle}
        />
        <input
          placeholder="session.id"
          value={filters.sessionId}
          onChange={(e) => setFilters(prev => ({ ...prev, sessionId: e.target.value }))}
          style={inputStyle}
          onFocus={focusStyle}
          onBlur={blurStyle}
        />
        <input
          placeholder="trace.id"
          value={filters.traceId}
          onChange={(e) => setFilters(prev => ({ ...prev, traceId: e.target.value }))}
          style={inputStyle}
          onFocus={focusStyle}
          onBlur={blurStyle}
        />
        <select
          value={filters.intent}
          onChange={(e) => setFilters(prev => ({ ...prev, intent: e.target.value }))}
          style={selectStyle}
        >
          <option value="">全部意图</option>
          <option value="TRANSFER">TRANSFER</option>
          <option value="BILL_QUERY">BILL_QUERY</option>
          <option value="WEALTH">WEALTH</option>
          <option value="CHAT">CHAT</option>
        </select>
        <select
          value={filters.agent}
          onChange={(e) => setFilters(prev => ({ ...prev, agent: e.target.value }))}
          style={selectStyle}
        >
          <option value="">全部智能体</option>
          <option value="L0">L0</option>
          <option value="TransferService">TransferService</option>
          <option value="BillService">BillService</option>
        </select>
        <button onClick={handleSearch} style={btnStyle}>查询</button>
      </div>

      {/* Trace 列表 */}
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
          <span>Trace 列表</span>
          <span style={{ fontSize: 11, color: 'rgba(0,0,0,.45)', fontWeight: 400 }}>
            {traces.length} 条
          </span>
        </div>
        <TraceTable
          traces={traces}
          onViewDetail={handleViewDetail}
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

      {/* Trace 详情 Modal */}
      <TraceDetailModal
        trace={selectedTrace}
        visible={modalVisible}
        onClose={handleCloseModal}
      />
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

const selectStyle = {
  padding: '6px 28px 6px 12px',
  border: '1px solid #d9d9d9',
  borderRadius: 6,
  fontSize: 13,
  background: '#fff',
  cursor: 'pointer',
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

function focusStyle(e) { e.target.style.borderColor = '#1677ff'; e.target.style.boxShadow = '0 0 0 2px rgba(22,119,255,.1)'; }
function blurStyle(e) { e.target.style.borderColor = '#d9d9d9'; e.target.style.boxShadow = 'none'; }

export default TraceExplorerPage;
