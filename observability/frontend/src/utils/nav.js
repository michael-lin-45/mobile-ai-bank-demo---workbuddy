/**
 * SPA 导航函数统一收口（任务分解 §7.4）。
 *
 * 禁止在各组件内散写 window.location / react-router 直跳。
 * 提供：itab（切 AI 洞察 TAB）/ sw（跨页路由）/ openTraceDetail（链路详情弹窗）/ openSessionReplay（会话回放）。
 *
 * 实现：基于 EventTarget 的轻量总线 + 可选 react-router navigate 注册。
 * 组件通过 on(name, handler) 订阅对应事件。
 */

const bus = new EventTarget();

/** 内部：派发命名事件 */
export function emit(name, detail) {
  bus.dispatchEvent(new CustomEvent(name, { detail }));
}

/** 内部：订阅命名事件，返回取消订阅函数 */
export function on(name, handler) {
  bus.addEventListener(name, handler);
  return () => bus.removeEventListener(name, handler);
}

// 由 App 根组件注册 react-router 的 navigate（一次）
let _navigate = null;
export function registerNavigate(navigate) {
  _navigate = navigate;
}

/** 跨页 SPA 路由切换：sw('dashboard') / sw('insights') */
export function sw(target) {
  if (_navigate) {
    _navigate(target);
  } else if (typeof window !== 'undefined') {
    window.location.hash = '#' + target;
  }
}

/** 切换 AI 洞察 TAB（如 'diagnosis'），并跳转到洞察页 */
export function itab(tabKey) {
  emit('ai-insights:tab', { tabKey });
  sw('/insights');
}

/** 打开链路详情弹窗（TraceDetailModal） */
export function openTraceDetail(traceId) {
  emit('trace:open', { traceId });
}

/** 打开会话回放 */
export function openSessionReplay(sessionId) {
  emit('session:replay', { sessionId });
  sw('/sessions');
}

export default {
  sw,
  itab,
  openTraceDetail,
  openSessionReplay,
  registerNavigate,
  on,
  emit,
};
