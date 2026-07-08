import React from 'react';

/**
 * SessionFilter — 会话回放筛选栏
 *
 * 6个筛选：Session ID / User ID / 渠道 / 意图 / 智能体 / 状态
 *
 * Props:
 * - filters: { sessionId, userId, channel, intent, agent, status }
 * - onChange: (name, value) => void
 * - onSearch: () => void
 */
function SessionFilter({ filters = {}, onChange, onSearch }) {
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
    appearance: 'none',
    outline: 'none',
    boxSizing: 'border-box',
  };

  const handleChange = (name) => (e) => {
    onChange(name, e.target.value);
  };

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
      <input
        style={inputStyle}
        placeholder="session.id"
        value={filters.sessionId || ''}
        onChange={handleChange('sessionId')}
        onFocus={(e) => { e.target.style.borderColor = '#1677ff'; e.target.style.boxShadow = '0 0 0 2px rgba(22,119,255,.1)'; }}
        onBlur={(e) => { e.target.style.borderColor = '#d9d9d9'; e.target.style.boxShadow = 'none'; }}
      />
      <input
        style={inputStyle}
        placeholder="user.id"
        value={filters.userId || ''}
        onChange={handleChange('userId')}
        onFocus={(e) => { e.target.style.borderColor = '#1677ff'; e.target.style.boxShadow = '0 0 0 2px rgba(22,119,255,.1)'; }}
        onBlur={(e) => { e.target.style.borderColor = '#d9d9d9'; e.target.style.boxShadow = 'none'; }}
      />
      <select
        style={selectStyle}
        value={filters.channel || ''}
        onChange={handleChange('channel')}
      >
        <option value="">全部渠道</option>
        <option value="APP">APP</option>
        <option value="小程序">小程序</option>
      </select>
      <select
        style={selectStyle}
        value={filters.intent || ''}
        onChange={handleChange('intent')}
      >
        <option value="">全部意图</option>
        <option value="CHAT">CHAT</option>
        <option value="TRANSFER">TRANSFER</option>
        <option value="WEALTH">WEALTH</option>
        <option value="BILL_QUERY">BILL_QUERY</option>
      </select>
      <select
        style={selectStyle}
        value={filters.agent || ''}
        onChange={handleChange('agent')}
      >
        <option value="">全部智能体</option>
        <option value="L0">L0</option>
        <option value="TransferService">TransferService</option>
        <option value="BillService">BillService</option>
        <option value="WealthConsult">WealthConsult</option>
      </select>
      <select
        style={selectStyle}
        value={filters.status || ''}
        onChange={handleChange('status')}
      >
        <option value="">全部状态</option>
        <option value="completed">正常</option>
        <option value="error">异常</option>
        <option value="slow">偏慢</option>
      </select>
      <button
        onClick={onSearch}
        style={{
          padding: '6px 16px',
          background: '#1677ff',
          color: '#fff',
          border: 'none',
          borderRadius: 6,
          fontSize: 13,
          cursor: 'pointer',
        }}
      >
        查询
      </button>
    </div>
  );
}

export default SessionFilter;
