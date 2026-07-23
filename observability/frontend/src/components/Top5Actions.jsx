import React, { useState, useEffect, useCallback } from 'react';
import { Tag, Empty, Spin } from 'antd';
import { fetchInsightsActions } from '../api/client';

/**
 * Top5 优化建议（V23 B6 / 任务分解 M8）。
 *
 * 水平紧凑条：取优化建议 Top10 的前 5 条，横向排列，点击优先级/严重度标签。
 * 供「智能诊断」TAB 顶部快速概览。
 */
const SEVERITY_COLOR = { HIGH: 'red', MED: 'orange', LOW: 'default' };

function Top5Actions() {
  const [loading, setLoading] = useState(true);
  const [actions, setActions] = useState([]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await fetchInsightsActions();
      setActions((data || []).slice(0, 5));
    } catch (err) {
      console.warn('[Top5Actions] load failed:', err.message);
      setActions([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  return (
    <div style={{
      background: '#fff', borderRadius: 8, boxShadow: '0 1px 2px rgba(0,0,0,.03)', padding: '12px 16px',
      display: 'flex', alignItems: 'center', gap: 12, overflowX: 'auto',
    }}>
      <span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)', whiteSpace: 'nowrap', fontWeight: 500 }}>Top5 优化建议</span>
      <Spin spinning={loading}>
        {actions.length === 0 && !loading ? (
          <Empty description="暂无建议" image={Empty.PRESENTED_IMAGE_SIMPLE} style={{ margin: 0 }} />
        ) : (
          <div style={{ display: 'flex', gap: 10, alignItems: 'stretch' }}>
            {actions.map((a, i) => (
              <div key={a.id || i} style={{
                display: 'flex', flexDirection: 'column', gap: 4, minWidth: 150, maxWidth: 200,
                padding: '8px 10px', borderRadius: 6, background: '#fafafa', border: '1px solid #f0f0f0',
              }}>
                <div style={{ fontSize: 12, fontWeight: 600, color: 'rgba(0,0,0,.88)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                  {i + 1}. {a.title}
                </div>
                <div style={{ display: 'flex', gap: 4 }}>
                  <Tag color={SEVERITY_COLOR[a.severity] || 'default'} style={{ margin: 0, fontSize: 10 }}>{a.severity}</Tag>
                  <Tag color="blue" style={{ margin: 0, fontSize: 10 }}>P{a.priority}</Tag>
                </div>
              </div>
            ))}
          </div>
        )}
      </Spin>
    </div>
  );
}

export default Top5Actions;
