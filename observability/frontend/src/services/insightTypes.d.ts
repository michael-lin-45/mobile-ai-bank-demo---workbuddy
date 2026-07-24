/**
 * insightTypes — AI 洞察页 mock schema 的类型声明（contract only）
 *
 * 纯 JS 项目，不强制 TS check；本文件仅用于编辑器提示与契约约束。
 * 字段对齐 docs/system_design.md §3 与《可观测V4-详细设计-§8.5》。
 */

export interface FunnelStage {
  name: string;
  value: number;
  completed?: number;
  abandoned?: number;
  conversionRate?: number;
  abandonRate?: number;
  reason?: string;
}

export interface FunnelAbandonSlice {
  name: string;
  value: number;
}

export interface FunnelDetailRow {
  stage: string;
  entered: number;
  completed: number;
  abandoned: number;
  abandonRate: number;
  reason?: string;
}

export interface ChurnItem {
  name: string;
  count: number;
  rate: number;
}

export interface ChurnProfile {
  intent: ChurnItem[];
  channel: ChurnItem[];
  time: ChurnItem[];
}

export interface ConversionFunnelMock {
  stages: FunnelStage[];
  abandonPie: FunnelAbandonSlice[];
  details: FunnelDetailRow[];
  churnProfile: ChurnProfile;
}

export interface AccuracyKpi {
  label: string;
  value: number;
  unit?: string;
}

export interface AccuracyTrend {
  categories: string[];
  series: { name: string; data: number[] }[];
}

export interface RewriteRow {
  scene: string;
  total: number;
  correct: number;
  rate: number;
  trend: number;
  errorExample: string;
}

export interface RootCause {
  rank: number;
  title: string;
  count: number;
  desc: string;
  pct: number;
  bg: string;
  color: string;
}

export interface ConfusionMatrix {
  labels: string[];
  matrix: number[][];
}

export interface AccuracyReportMock {
  overallStats: AccuracyKpi[];
  trend: AccuracyTrend;
  rewrite: RewriteRow[];
  rootCauses: RootCause[];
  confusion: ConfusionMatrix;
}

export type InvocationCategory = 'skill' | 'mcp' | 'tool' | 'rag';

export interface InvocationRecordMock {
  category: InvocationCategory;
  name: string;
  calls: number;
  success: number;
  failed: number;
  avgLatencyMs: number;
  p95LatencyMs: number;
  errorRate: number;
  typicalError?: string;
}

export interface InvocationSummaryMock {
  totalCalls: number;
  overallSuccessRate: number;
  avgLatencyMs: number;
  p95LatencyMs: number;
  totalFailed?: number;
  errorRate?: number;
}

export interface SlowSessionRow {
  sessionId: string;
  durationSec: number;
  rootCause: string;
  bottleneck: string;
}

export interface UnsatisfiedCluster {
  theme: string;
  count: number;
  rate: number;
  sample: string;
}

export interface BottleneckCard {
  type: 'perf' | 'accuracy' | 'conversion';
  title: string;
  value: number;
  unit?: string;
  desc: string;
}

export interface AgentScatterPoint {
  agent: string;
  p95Ms: number;
  errorRate: number;
}

export interface DiagnosisMocks {
  snapshot: { summary: string; generatedAt: string; boundary?: string };
  topActions: { rank: number; title: string; impact: string }[];
  bottlenecks: BottleneckCard[];
  slowSessions: SlowSessionRow[];
  unsatisfied: UnsatisfiedCluster[];
  agentScatter: AgentScatterPoint[];
  conversionProgress: { stage: string; rate: number }[];
}
