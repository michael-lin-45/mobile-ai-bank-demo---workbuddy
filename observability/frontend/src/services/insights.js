import { fetchRealtimeMetrics, fetchBusinessEventCount } from '../api/client';

/**
 * 洞察数据服务（V23 B3 统一取数封装，§7）。
 *
 * 总览 Zone C/D 与诊断摘要 4 卡统一从这里取数：
 * - getRealtime(): GET /metrics/realtime（业务完成率 / 重路由比率 / 意图 L0/L1 等）
 * - getCardClickCount / getHumanClickCount: 业务埋点计数（BusinessEventQueryService）
 */

/** 实时指标（Zone C / 诊断摘要 4 卡数据源） */
export function getRealtime() {
  return fetchRealtimeMetrics();
}

/** 业务引导办理次数（Zone D 卡②） */
export async function getCardClickCount(from, to) {
  return fetchBusinessEventCount('mbank_card_click', from, to);
}

/** 转人工次数（Zone D 卡③） */
export async function getHumanClickCount(from, to) {
  return fetchBusinessEventCount('mbank_human_click', from, to);
}

export default {
  getRealtime,
  getCardClickCount,
  getHumanClickCount,
};
