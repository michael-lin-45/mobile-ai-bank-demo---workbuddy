/**
 * mockInsights — AI 洞察页前端 mock 数据（真实优先、空则回退）
 *
 * 设计哲学（docs/system_design.md §1.2）：真实 fetch 失败/空 → 回退此处数据。
 * 各 schema 字段对齐《可观测V4-详细设计-§8.5》后端契约，并经 insightAdapters 归一。
 *
 * 量级（Q2）：funnel 6 层 / rootCauses 3 / churn 每组 4-5 / invocations 各 5-6。
 */

import { KPI_COLOR, KPI_BG } from '../theme/insightTokens';

/* ───────── 业务转化漏斗（ConversionFunnelMock） ───────── */

export const getConversionFunnelMock = () => ({
  // stages 直接给 {name,value}，FunnelChart 不再 undefined（Q5 兼容由 adapter 处理真实 {stage,count}）
  stages: [
    { name: '访问会话', value: 1000, reason: '入口流量' },
    { name: '意图识别成功', value: 920, reason: '少量识别失败' },
    { name: '进入业务办理', value: 870, reason: '用户犹豫退出' },
    { name: '业务信息填写', value: 760, reason: '表单过长放弃' },
    { name: '业务提交', value: 700, reason: '校验失败重试' },
    { name: '业务成功完成', value: 650, reason: '完成' },
  ],
  abandonPie: [
    { name: '意图识别失败', value: 80 },
    { name: '主动退出', value: 110 },
    { name: '表单放弃', value: 110 },
    { name: '校验失败', value: 60 },
    { name: '其他', value: 50 },
  ],
  details: [
    { stage: '访问会话', entered: 1000, completed: 1000, abandoned: 0, conversionRate: 100, abandonRate: 0, reason: '入口流量' },
    { stage: '意图识别', entered: 1000, completed: 920, abandoned: 80, conversionRate: 92, abandonRate: 8, reason: '识别失败' },
    { stage: '进入业务办理', entered: 920, completed: 870, abandoned: 50, conversionRate: 94.6, abandonRate: 5.4, reason: '用户犹豫退出' },
    { stage: '业务信息填写', entered: 870, completed: 760, abandoned: 110, conversionRate: 87.4, abandonRate: 12.6, reason: '表单过长放弃' },
    { stage: '业务提交', entered: 760, completed: 700, abandoned: 60, conversionRate: 92.1, abandonRate: 7.9, reason: '校验失败重试' },
    { stage: '业务完成', entered: 700, completed: 650, abandoned: 50, conversionRate: 92.9, abandonRate: 7.1, reason: '完成' },
  ],
  // 流失画像（每组 4-5 条，修复"空画像"）
  churnProfile: {
    intent: [
      { name: '转账', count: 210, rate: 21 },
      { name: '账单', count: 120, rate: 12 },
      { name: '理财咨询', count: 95, rate: 9.5 },
      { name: '理财解读', count: 70, rate: 7 },
      { name: '闲聊', count: 40, rate: 4 },
    ],
    channel: [
      { name: 'APP', count: 320, rate: 14 },
      { name: '小程序', count: 180, rate: 11 },
      { name: 'H5', count: 90, rate: 18 },
      { name: '微信公众号', count: 60, rate: 9 },
    ],
    time: [
      { name: '上午(9-12)', count: 150, rate: 10 },
      { name: '下午(12-18)', count: 240, rate: 12 },
      { name: '晚间(18-22)', count: 210, rate: 16 },
      { name: '深夜(22-9)', count: 80, rate: 21 },
    ],
  },
});

/* ───────── 准确率分析报告（AccuracyReportMock） ───────── */

export const getAccuracyReportMock = () => ({
  overallStats: [
    { label: '整体意图准确率', value: 94.2, unit: '%' },
    { label: '改写准确率', value: 91.5, unit: '%' },
    { label: '混淆率', value: 3.8, unit: '%' },
    { label: '样本量', value: 12840, unit: '条' },
  ],
  trend: {
    categories: ['第1周', '第2周', '第3周', '第4周', '第5周', '第6周'],
    series: [
      { name: '转账', data: [92, 93, 93.5, 94, 94.5, 95] },
      { name: '账单', data: [90, 91, 91, 92, 92.5, 93] },
      { name: '理财咨询', data: [88, 89, 90, 90.5, 91, 92] },
      { name: '理财解读', data: [86, 87, 88, 89, 90, 91] },
      { name: '闲聊', data: [95, 95.5, 96, 96, 96.5, 97] },
    ],
  },
  // 改写准确率分析表（5-6 行）
  rewrite: [
    { scene: '转账金额归一', total: 3200, correct: 3120, rate: 97.5, trend: 1.2, errorExample: '「两万」识别为 2000' },
    { scene: '账单周期解析', total: 2100, correct: 1980, rate: 94.3, trend: 0.8, errorExample: '「上月」偏移一月' },
    { scene: '理财风险偏好', total: 1800, correct: 1620, rate: 90.0, trend: -1.5, errorExample: '保守/激进 误判' },
    { scene: '产品名实体对齐', total: 2500, correct: 2300, rate: 92.0, trend: 0.5, errorExample: '「朝朝宝」别名缺失' },
    { scene: '时间表达式', total: 1900, correct: 1780, rate: 93.7, trend: 1.0, errorExample: '「后天」跨月计算错' },
    { scene: '负向意图拒答', total: 1340, correct: 1190, rate: 88.8, trend: -2.1, errorExample: '高风险诉求未拦截' },
  ],
  // 改写失败根因 TOP3（修复空表）
  rootCauses: [
    {
      rank: 1,
      title: '实体别名缺失',
      count: 420,
      desc: '产品/机构别名未收录，导致实体对齐失败，改写后意图漂移。',
      pct: 0.34,
      bg: KPI_BG.red,
      color: KPI_COLOR.red,
    },
    {
      rank: 2,
      title: '时间表达式歧义',
      count: 310,
      desc: '相对时间（上月/后天）跨月、跨年边界计算错误，触发改写失败。',
      pct: 0.25,
      bg: KPI_BG.orange,
      color: KPI_COLOR.orange,
    },
    {
      rank: 3,
      title: '风险意图误放行',
      count: 190,
      desc: '负向/高风险诉求未正确拒答，改写阶段置信度不足。',
      pct: 0.15,
      bg: KPI_BG.purple,
      color: KPI_COLOR.purple,
    },
  ],
  confusion: {
    labels: ['转账', '账单', '理财咨询', '理财解读', '闲聊'],
    matrix: [
      [920, 30, 12, 8, 30],
      [25, 880, 18, 10, 67],
      [14, 20, 760, 140, 66],
      [9, 12, 130, 770, 79],
      [22, 18, 14, 11, 980],
    ],
  },
});

/* ───────── 外部调用（InvocationRecordMock） ───────── */

// SKILL 段 mock（参考 MCP 表列：工具/调用/成功/失败/平均/P95/错误率/典型错误）
export const getSkillInvocationsMock = () => [
  { category: 'skill', name: 'skill.transfer', calls: 220, success: 218, failed: 2, avgLatencyMs: 340, p95LatencyMs: 700, errorRate: 0.9, typicalError: '授权失败' },
  { category: 'skill', name: 'skill.recommend', calls: 180, success: 180, failed: 0, avgLatencyMs: 200, p95LatencyMs: 460, errorRate: 0, typicalError: null },
  { category: 'skill', name: 'skill.riskCheck', calls: 160, success: 154, failed: 6, avgLatencyMs: 280, p95LatencyMs: 620, errorRate: 3.8, typicalError: '模型超时' },
  { category: 'skill', name: 'skill.summarize', calls: 140, success: 138, failed: 2, avgLatencyMs: 360, p95LatencyMs: 780, errorRate: 1.4, typicalError: null },
  { category: 'skill', name: 'skill.translate', calls: 120, success: 119, failed: 1, avgLatencyMs: 240, p95LatencyMs: 540, errorRate: 0.8, typicalError: null },
  { category: 'skill', name: 'skill.classify', calls: 98, success: 96, failed: 2, avgLatencyMs: 190, p95LatencyMs: 430, errorRate: 2.0, typicalError: '置信度不足' },
];

// MCP 段 mock
export const getMcpInvocationsMock = () => [
  { category: 'mcp', name: 'MCP:queryBalance', calls: 640, success: 638, failed: 2, avgLatencyMs: 210, p95LatencyMs: 480, errorRate: 0.3, typicalError: null },
  { category: 'mcp', name: 'MCP:transfer', calls: 220, success: 218, failed: 2, avgLatencyMs: 340, p95LatencyMs: 700, errorRate: 0.9, typicalError: '授权失败' },
  { category: 'mcp', name: 'MCP:fundDetail', calls: 150, success: 150, failed: 0, avgLatencyMs: 260, p95LatencyMs: 540, errorRate: 0, typicalError: null },
  { category: 'mcp', name: 'MCP:creditScore', calls: 130, success: 127, failed: 3, avgLatencyMs: 380, p95LatencyMs: 820, errorRate: 2.3, typicalError: '下游限流' },
  { category: 'mcp', name: 'MCP:productSearch', calls: 110, success: 108, failed: 2, avgLatencyMs: 300, p95LatencyMs: 660, errorRate: 1.8, typicalError: null },
  { category: 'mcp', name: 'MCP:kycVerify', calls: 90, success: 89, failed: 1, avgLatencyMs: 420, p95LatencyMs: 900, errorRate: 1.1, typicalError: null },
];

// 全部段顶部 KPI 汇总
export const getInvocationSummaryMock = () => {
  const all = [...getSkillInvocationsMock(), ...getMcpInvocationsMock()];
  const totalCalls = all.reduce((a, r) => a + r.calls, 0);
  const totalFailed = all.reduce((a, r) => a + r.failed, 0);
  const totalSuccess = all.reduce((a, r) => a + r.success, 0);
  const avgLatency = Math.round(all.reduce((a, r) => a + r.avgLatencyMs, 0) / all.length);
  const p95 = Math.max(...all.map((r) => r.p95LatencyMs));
  return {
    totalCalls,
    overallSuccessRate: totalCalls > 0 ? Number(((totalSuccess / totalCalls) * 100).toFixed(2)) : 0,
    avgLatencyMs: avgLatency,
    p95LatencyMs: p95,
    totalFailed,
    errorRate: totalCalls > 0 ? Number(((totalFailed / totalCalls) * 100).toFixed(2)) : 0,
  };
};

/* ───────── 整合诊断（DiagnosisMocks，供 DiagnosisUnified 兜底） ───────── */

export const getDiagnosisMocks = () => ({
  snapshot: {
    summary: '洞察摘要：检测到 3 类瓶颈信号、5 条优先行动建议、1 类不满意聚类；整体转化漏斗健康度良好。',
    generatedAt: '2026-07-23T10:00:00Z',
    boundary: 'L1',
  },
  // 优先行动 TOP5
  topActions: [
    { rank: 1, title: '优化转账表单长度', impact: '预计降低表单放弃率 12%' },
    { rank: 2, title: '补齐实体别名词典', impact: '提升改写准确率 3.5pt' },
    { rank: 3, title: '加固时间表达式解析', impact: '减少跨月计算错误' },
    { rank: 4, title: '风险意图二次校验', impact: '降低误放行率' },
    { rank: 5, title: '晚间时段扩容', impact: '缓解深夜流失高峰' },
  ],
  // 三类瓶颈卡（perf / accuracy / conversion）
  bottlenecks: [
    { type: 'perf', title: 'P95 端到端时延', value: 820, unit: 'ms', desc: '近 6h P95 时延偏高，主要集中转账链路。' },
    { type: 'accuracy', title: '改写失败率', value: 8.5, unit: '%', desc: '实体别名与时间表达式为主要失败来源。' },
    { type: 'conversion', title: '表单放弃率', value: 12.6, unit: '%', desc: '业务信息填写阶段放弃率偏高。' },
  ],
  // 慢会话根因表
  slowSessions: [
    { sessionId: 'S-1001', durationSec: 18.2, rootCause: '转账链路下游超时叠加', bottleneck: 'MCP:transfer P95 700ms' },
    { sessionId: 'S-1002', durationSec: 15.6, rootCause: '实体对齐重试 3 次', bottleneck: 'skill.classify 置信度不足' },
    { sessionId: 'S-1003', durationSec: 14.1, rootCause: '表单校验失败循环', bottleneck: '前端校验逻辑' },
    { sessionId: 'S-1004', durationSec: 12.8, rootCause: 'RAG 检索召回慢', bottleneck: 'rag.retrieve 260ms' },
  ],
  // 不满意共性表
  unsatisfied: [
    { theme: '转账失败', count: 80, rate: 12, sample: 'S-1001、S-1002' },
    { theme: '表单繁琐', count: 60, rate: 9, sample: 'S-1003' },
    { theme: '回答不准确', count: 45, rate: 7, sample: 'S-1005' },
    { theme: '等待过久', count: 38, rate: 6, sample: 'S-1004' },
  ],
  // Agent P95 vs 错误率散点
  agentScatter: [
    { agent: 'TransferAgent', p95Ms: 1620, errorRate: 2.4 },
    { agent: 'QueryAgent', p95Ms: 980, errorRate: 0.9 },
    { agent: 'AdvisoryAgent', p95Ms: 1340, errorRate: 1.6 },
    { agent: 'KycAgent', p95Ms: 1180, errorRate: 1.1 },
    { agent: 'GeneralAgent', p95Ms: 760, errorRate: 0.4 },
  ],
  // 转化漏斗 Progress 条
  conversionProgress: [
    { stage: '访问会话', rate: 100 },
    { stage: '意图识别成功', rate: 92 },
    { stage: '进入业务办理', rate: 87 },
    { stage: '业务信息填写', rate: 76 },
    { stage: '业务提交', rate: 70 },
    { stage: '业务成功完成', rate: 65 },
  ],
});
