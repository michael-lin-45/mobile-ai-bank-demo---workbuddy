import { ingestBusinessEvent, fetchBusinessEventCount } from '../api/client';

/**
 * 业务埋点 SDK 封装（V23 M1/M2，§7.1）。
 *
 * 统一收口前端埋点 track 调用，禁止在各组件内散写 fetch。
 * track 事件名 / 属性维度严格对齐任务分解：
 *   track('mbank_card_click',  { session_id, trace_id, agent, card_type, ts })
 *   track('mbank_human_click', { session_id, trace_id, agent, card_type, source, ts })
 *
 * 时间统一北京时间（UTC+8），ts 输出 ISO-8601（+08:00）。
 */

const DEFAULT_AGENT = 'bankAssistant';

/** 当前北京时间 ISO-8601（+08:00） */
function nowBeijingIso() {
  const d = new Date();
  const local = new Date(d.getTime() + (d.getTimezoneOffset() + 8 * 60) * 60000);
  return local.toISOString().replace('Z', '+08:00');
}

/**
 * 通用埋点上报。
 * @param {string} eventName mbank_card_click | mbank_human_click
 * @param {object} props 埋点属性
 */
export function track(eventName, props = {}) {
  const payload = {
    session_id: props.session_id || null,
    trace_id: props.trace_id || null,
    user_id: props.user_id || null,
    agent: props.agent || DEFAULT_AGENT,
    event_type: eventName,
    card_type: props.card_type || null,
    source: props.source || null,
    channel: props.channel || null,
    ts: props.ts || nowBeijingIso(),
  };
  return ingestBusinessEvent(payload).catch((e) => {
    // 埋点失败不应阻断主业务
    console.warn('[businessEvents] track failed:', e.message);
  });
}

/** 业务引导办理埋点（必传 card_type） */
export function trackCardClick({ session_id, trace_id, agent, card_type, channel, ts }) {
  return track('mbank_card_click', { session_id, trace_id, agent, card_type, channel, ts });
}

/** 转人工埋点（必传 source: chat_bar|card_menu|timeout） */
export function trackHumanClick({ session_id, trace_id, agent, source, channel, ts }) {
  return track('mbank_human_click', { session_id, trace_id, agent, source, channel, ts });
}

/** 查询业务引导办理次数（mbank_card_click） */
export async function getCardClickCount(from, to) {
  return fetchBusinessEventCount('mbank_card_click', from, to);
}

/** 查询转人工次数（mbank_human_click） */
export async function getHumanClickCount(from, to) {
  return fetchBusinessEventCount('mbank_human_click', from, to);
}

export default {
  track,
  trackCardClick,
  trackHumanClick,
  getCardClickCount,
  getHumanClickCount,
};
