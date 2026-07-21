import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Tooltip } from 'antd';
import {
  fetchHealth,
  fetchRealtimeMetrics,
  fetchStorageConfig,
  fetchTraces,
} from '../api/client';
import { formatBeijingTime } from '../utils/time';

/**
 * HealthBadge — 数据健康三态角标（T-I）
 *
 * 基于真实后端健康探针计算三态（设计 §13.1）：
 *   HEALTHY (健康/绿)  — 全部数据源探针正常
 *   PARTIAL (降级/黄)  — 后端存活，但某子数据源（指标/存储/链路）不可达或异常
 *   DOWN    (异常/红)  — 后端本身不可达（/health 非 UP）
 *
 * 数据源（5 类，对应设计 Core发射/Collector/H2/Redis/前端）：
 *   后端聚合 API  ← GET /health
 *   指标数据源    ← GET /metrics/realtime
 *   存储(H2/Redis)← GET /settings/storage (redisStatus)
 *   链路(Collector)← GET /traces?size=1
 *   前端本应用    ← 恒为健康（自探测）
 *
 * 轮询间隔 15s；后端未就绪时整体降级为 DOWN，不注入假数据。
 */

// 三态展示配置
const STATE_CONFIG = {
  HEALTHY: { color: '#52c41a', bg: '#f6ffed', border: '#b7eb8f', label: '健康' },
  PARTIAL: { color: '#faad14', bg: '#fffbe6', border: '#ffe58f', label: '降级' },
  DOWN: { color: '#ff4d4f', bg: '#fff2f0', border: '#ffccc7', label: '异常' },
};

// 单源探测：成功返回 {status:'healthy'}；失败/超时返回 {status:'down', error}
async function probe(promiseFn) {
  try {
    await promiseFn();
    return { status: 'healthy', error: null };
  } catch (err) {
    return { status: 'down', error: err?.message || 'unknown' };
  }
}

function HealthBadge() {
  const [state, setState] = useState('HEALTHY');
  const [sources, setSources] = useState({});
  const [lastChecked, setLastChecked] = useState(null);
  const [checking, setChecking] = useState(false);
  // 防止并发轮询/卸载后 setState
  const aliveRef = useRef(true);

  const check = useCallback(async () => {
    if (!aliveRef.current) return;
    setChecking(true);

    // 1) 后端聚合 API（决定整体是否 DOWN）
    const backendProbe = await probe(async () => {
      const h = await fetchHealth();
      const status = h?.data?.status;
      if (status !== 'UP') throw new Error(`status=${status}`);
    });

    // 2) 指标数据源（Redis 热层）
    const metricsProbe = await probe(() => fetchRealtimeMetrics());

    // 3) 存储（H2 / Redis）
    const storageProbe = await probe(async () => {
      const s = await fetchStorageConfig();
      if (!s || !s.redisStatus) throw new Error('redisStatus 缺失');
    });

    // 4) 链路（Collector 是否仍在喂数据）
    const collectorProbe = await probe(() =>
      fetchTraces({ size: 1, page: 0 })
    );

    // 5) 前端本应用（自探测，恒健康）
    const frontendProbe = { status: 'healthy', error: null };

    const nextSources = {
      后端聚合API: backendProbe,
      指标数据源: metricsProbe,
      存储H2Redis: storageProbe,
      链路Collector: collectorProbe,
      前端应用: frontendProbe,
    };

    // 三态判定口径
    let nextState;
    if (backendProbe.status === 'down') {
      nextState = 'DOWN';
    } else if (
      metricsProbe.status === 'down' ||
      storageProbe.status === 'down' ||
      collectorProbe.status === 'down'
    ) {
      nextState = 'PARTIAL';
    } else {
      nextState = 'HEALTHY';
    }

    if (aliveRef.current) {
      setState(nextState);
      setSources(nextSources);
      setLastChecked(new Date());
      setChecking(false);
    }
  }, []);

  useEffect(() => {
    aliveRef.current = true;
    check();
    const timer = setInterval(check, 15000);
    return () => {
      aliveRef.current = false;
      clearInterval(timer);
    };
  }, [check]);

  const cfg = STATE_CONFIG[state] || STATE_CONFIG.PARTIAL;
  const dotColor = state === 'HEALTHY' ? cfg.color : cfg.color;

  const tooltipText = (
    <div style={{ fontSize: 12, lineHeight: 1.8 }}>
      <div style={{ fontWeight: 600, marginBottom: 4 }}>
        数据健康：{cfg.label}
      </div>
      {Object.entries(sources).map(([name, s]) => (
        <div key={name} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span
            style={{
              width: 7,
              height: 7,
              borderRadius: '50%',
              background: s.status === 'healthy' ? '#52c41a' : '#ff4d4f',
              display: 'inline-block',
            }}
          />
          <span>{name}</span>
          <span style={{ color: s.status === 'healthy' ? '#52c41a' : '#ff4d4f' }}>
            {s.status === 'healthy' ? '正常' : '异常'}
          </span>
        </div>
      ))}
      {lastChecked && (
        <div style={{ marginTop: 4, color: 'rgba(255,255,255,.65)' }}>
          最近检测：{formatBeijingTime(lastChecked)}
        </div>
      )}
    </div>
  );

  return (
    <Tooltip title={tooltipText} placement="bottomRight">
      <div
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 6,
          padding: '3px 10px',
          borderRadius: 14,
          fontSize: 12,
          fontWeight: 500,
          cursor: 'default',
          color: cfg.color,
          background: cfg.bg,
          border: `1px solid ${cfg.border}`,
          fontFamily: '"JetBrains Mono", monospace',
        }}
      >
        <span
          style={{
            width: 8,
            height: 8,
            borderRadius: '50%',
            background: dotColor,
            display: 'inline-block',
            boxShadow: state === 'HEALTHY' ? '0 0 0 3px rgba(82,196,26,.18)' : 'none',
            opacity: checking ? 0.5 : 1,
          }}
        />
        数据健康：{cfg.label}
      </div>
    </Tooltip>
  );
}

export default HealthBadge;
