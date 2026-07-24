/**
 * insightAdapters — AI 洞察页字段映射 / 归一 / 工具函数
 *
 * 集中维护，避免各 TAB 散落重复逻辑（docs/system_design.md §8 共享知识）：
 *   - isEmpty:            空判定（null/undefined/空数组/纯空对象）
 *   - toFunnelChartData:  stages → [{name, value}]（兼容后端 {stage,count}）
 *   - toInvocationRows:   归一 InvocationRecord（兼容 VO callCount 等字段）
 *   - safeRate:           安全除法（分母 0 → 0）
 *   - formatThousands:    确定性千分位格式化（避免 locale 差异）
 */

/**
 * 空判定：真实 fetch 失败后回退 mock 的依据。
 * - null / undefined → 空
 * - 空数组 → 空
 * - 纯对象：所有值均为 null/空串/空数组 → 视为空（后端返回空壳）
 * @param {*} value
 * @returns {boolean}
 */
export function isEmpty(value) {
  if (value == null) return true;
  if (Array.isArray(value)) return value.length === 0;
  if (typeof value === 'object') {
    const keys = Object.keys(value);
    if (keys.length === 0) return true;
    return keys.every((k) => {
      const v = value[k];
      return v == null || v === '' || (Array.isArray(v) && v.length === 0);
    });
  }
  return false;
}

/**
 * 漏斗 stages → FunnelChart 期望的 {name, value}。
 * 兼容后端两种字段命名：{name, value} 或 {stage, count}（见 Q5）。
 * 缺失字段以 undefined 填充，FunnelChart 空值守卫会降级 Empty。
 *
 * @param {Array<{name?:string,stage?:string,value?:number,count?:number}>} stages
 * @returns {Array<{name:string,value:number,reason?:string}>}
 */
export function toFunnelChartData(stages) {
  if (!Array.isArray(stages)) return [];
  return stages.map((s) => ({
    name: s.name != null ? s.name : (s.stage != null ? s.stage : '—'),
    value: s.value != null ? s.value : (s.count != null ? s.count : 0),
    reason: s.reason,
  }));
}

/**
 * 归一外部调用记录为表格行（扁平命名）。
 * 兼容后端 InvocationRecord VO 字段（callCount/successCount/failCount/avgDurationMs/...）。
 *
 * @param {Array} records
 * @returns {Array<{category,name,calls,success,failed,avgLatencyMs,p95LatencyMs,errorRate,typicalError}>}
 */
export function toInvocationRows(records) {
  if (!Array.isArray(records)) return [];
  return records.map((r) => ({
    category: r.category,
    name: r.name,
    calls: r.calls != null ? r.calls : (r.callCount != null ? r.callCount : 0),
    success: r.success != null ? r.success : (r.successCount != null ? r.successCount : 0),
    failed: r.failed != null ? r.failed : (r.failCount != null ? r.failCount : 0),
    avgLatencyMs: r.avgLatencyMs != null ? r.avgLatencyMs : (r.avgDurationMs != null ? r.avgDurationMs : 0),
    p95LatencyMs: r.p95LatencyMs != null ? r.p95LatencyMs : (r.p95DurationMs != null ? r.p95DurationMs : 0),
    errorRate: r.errorRate != null ? r.errorRate : 0,
    typicalError: r.typicalError != null ? r.typicalError : (r.lastError != null ? r.lastError : undefined),
  }));
}

/**
 * 安全比率：分母 0 → 0。
 * @param {number} num
 * @param {number} den
 * @returns {number}
 */
export function safeRate(num, den) {
  if (!den) return 0;
  return num / den;
}

/**
 * 确定性千分位格式化（不依赖运行环境 locale / ICU）。
 * @param {number} n
 * @returns {string}
 */
export function formatThousands(n) {
  if (n == null || isNaN(n)) return '—';
  const [intPart, decPart] = String(n).split('.');
  const withSep = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return decPart != null ? `${withSep}.${decPart}` : withSep;
}
