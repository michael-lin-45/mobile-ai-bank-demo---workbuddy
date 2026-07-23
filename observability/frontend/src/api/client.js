import axios from 'axios';

/**
 * Axios 实例 — 统一后端 API 调用
 *
 * baseURL: /api/v1 (Vite proxy → http://127.0.0.1:9090)
 * 自动包装 ApiResponse 格式 { code, message, data }
 */
const client = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
});

// 响应拦截器：统一解包 ApiResponse
client.interceptors.response.use(
  (response) => {
    const body = response.data;
    if (body && typeof body.code === 'number') {
      if (body.code === 0) {
        return body.data;
      }
      return Promise.reject(new Error(body.message || `API error code=${body.code}`));
    }
    return body;
  },
  (error) => {
    console.error('[API] Request failed:', error.message);
    return Promise.reject(error);
  }
);

// ── Metrics API ──

export function fetchRealtimeMetrics() {
  return client.get('/metrics/realtime');
}

export function fetchMetricsHistory(from, to, step = '5m') {
  return client.get('/metrics/history', { params: { from, to, step } });
}

/** 请求 & Token 趋势 — GET /metrics/trend?hours=6（后端按 30min 分桶） */
export function fetchMetricsTrend(hours = 6) {
  return client.get('/metrics/trend', { params: { hours } });
}

// ── Trace API ──

export function fetchTraces(params = {}) {
  const { from, intent, limit = 500, userId, sessionId, traceId, status, agent, page = 0, size = 10 } = params;
  return client.get('/traces', {
    params: { from, intent, limit, userId, sessionId, traceId, status, agent, page, size },
  });
}

export function fetchTraceDetail(traceId) {
  return client.get(`/traces/${traceId}`);
}

// ── Session API ──

export function fetchSessions(params = {}) {
  const { userId, sessionId, channel, intent, agent, status, page, size } = params;
  return client.get('/sessions', {
    params: { userId, sessionId, channel, intent, agent, status, page, size },
  });
}

export function fetchSessionDetail(sessionId) {
  return client.get(`/sessions/${sessionId}`);
}

// ── Logs API ──

export function fetchLogs({ from, to, level, traceId, q, userId, sessionId, page, size } = {}) {
  return client.get('/logs', {
    params: { from, to, level, traceId, q, userId, sessionId, page, size },
  });
}

// ── AI Insights API ──

export function fetchAIInsights(type, from, to) {
  return client.get('/ai/insights', { params: { type, from, to } });
}

/** 意图混淆矩阵 — GET /ai/confusion-matrix */
export function fetchConfusionMatrix(params = {}) {
  return client.get('/ai/confusion-matrix', { params });
}

/** 统一准确率分析报告 — GET /ai/accuracy-report（趋势+改写表+根因+混淆矩阵） */
export function fetchAIAccuracyReport(params = {}) {
  return client.get('/ai/accuracy-report', { params });
}

/** Agent/LLM 性能 — GET /ai/agent-performance?dimension=agent|llm */
export function fetchAgentPerformance(params = {}) {
  return client.get('/ai/agent-performance', { params });
}

/** Token 成本分析 — GET /ai/token-cost?type=trend|breakdown|detail */
export function fetchTokenCost(params = {}) {
  return client.get('/ai/token-cost', { params });
}

/** 工具调用统计 — GET /ai/tool-stats */
export function fetchToolStats(params = {}) {
  return client.get('/ai/tool-stats', { params });
}

/** 业务转化漏斗 — GET /ai/conversion-funnel */
export function fetchConversionFunnel(params = {}) {
  return client.get('/ai/conversion-funnel', { params });
}

/** 用户满意度 — GET /ai/satisfaction */
export function fetchSatisfaction(params = {}) {
  return client.get('/ai/satisfaction', { params });
}

/** 提交满意度评价 — POST /ai/satisfaction */
export function submitSatisfaction(data) {
  return client.post('/ai/satisfaction', data);
}

// ── Insights Engine API（诊断驾驶舱 / 智能洞察 T-H 数据源）──

/** 聚合洞察报告（带缓存）— GET /ai/insights-report */
export function fetchInsightsReport() {
  return client.get('/ai/insights-report');
}

/** 性能 / 准确率 / 转化瓶颈（L1）— GET /ai/insights/bottlenecks */
export function fetchBottlenecks() {
  return client.get('/ai/insights/bottlenecks');
}

/** 慢会话根因（L2）— GET /ai/insights/root-cause/{sessionId} */
export function fetchRootCause(sessionId) {
  return client.get(`/ai/insights/root-cause/${encodeURIComponent(sessionId)}`);
}

/** 不满意会话共性聚类（L2）— GET /ai/insights/unsatisfied */
export function fetchUnsatisfied() {
  return client.get('/ai/insights/unsatisfied');
}

/** 转化漏斗（L1）— GET /ai/insights/conversion */
export function fetchInsightsConversion() {
  return client.get('/ai/insights/conversion');
}

/** 优化建议 Top10（L2）— GET /ai/insights/actions */
export function fetchInsightsActions() {
  return client.get('/ai/insights/actions');
}

/** 清缓存重算 — POST /ai/insights/refresh */
export function refreshInsights() {
  return client.post('/ai/insights/refresh');
}

// ── Business Events API（V23 M1/M2 真实业务埋点）──

/** 业务埋点入库 — POST /business-events */
export function ingestBusinessEvent(data) {
  return client.post('/business-events', data);
}

/** 业务事件计数 — GET /business-events/count?event=&from=&to= */
export function fetchBusinessEventCount(event, from, to) {
  return client.get('/business-events/count', {
    params: { event, from, to },
  });
}

/** 业务事件聚合 — GET /business-events/agg?event=&groupBy=&from=&to= */
export function fetchBusinessEventAgg(event, groupBy, from, to) {
  return client.get('/business-events/agg', {
    params: { event, groupBy, from, to },
  });
}

// ── External Invocation API（V23 M5/M10 统一外部调用台账）──

/** 外部调用记录 — GET /invocations?category={all|rag|tool|skill|mcp} */
export function fetchInvocations(category = 'all', provider, range) {
  return client.get('/invocations', {
    params: { category, provider, range },
  });
}

// ── Health API ──

export function fetchHealth() {
  return axios.get('/health').then(r => r.data);
}

// ── Alert Rules API ──

/** 获取告警规则列表 — GET /alerts/rules */
export function fetchAlertRules() {
  return client.get('/alerts/rules');
}

/** 创建告警规则 — POST /alerts/rules */
export function createAlertRule(data) {
  return client.post('/alerts/rules', data);
}

/** 更新告警规则 — PUT /alerts/rules/{id} */
export function updateAlertRule(id, data) {
  return client.put(`/alerts/rules/${id}`, data);
}

/** 删除告警规则 — DELETE /alerts/rules/{id} */
export function deleteAlertRule(id) {
  return client.delete(`/alerts/rules/${id}`);
}

/** 获取告警事件 — GET /alerts/events */
export function fetchAlertEvents(params = {}) {
  return client.get('/alerts/events', { params });
}

// ── Settings API ──

/** 获取系统设置 — GET /settings */
export function fetchSettings() {
  return client.get('/settings');
}

/** 获取存储配置（含 redisStatus，用于数据健康三态角标 T-I）— GET /settings/storage */
export function fetchStorageConfig() {
  return client.get('/settings/storage');
}

export default client;
