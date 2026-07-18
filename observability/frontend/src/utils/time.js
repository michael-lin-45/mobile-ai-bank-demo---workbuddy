/**
 * 时间格式化工具 — 统一按「北京时间（Asia/Shanghai）」展示
 *
 * 后端存储的 Instant 本质是 UTC，序列化到前端可能是：
 *   - ISO 字符串（默认）：  "2026-07-18T13:25:00.123Z"
 *   - 带时区偏移（配 spring.jackson.time-zone 后）： "2026-07-18T21:25:00.123+08:00"
 *   - 毫秒时间戳（数字）：  1752871500123
 * 无论哪种输入，本函数都转换为北京时间（东八区）的 "YYYY-MM-DD HH:mm:ss"。
 */

const BJ_FMT = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

/**
 * 将任意时间值格式化为北京时间字符串。
 * @param {string|number|null|undefined} value ISO 字符串 / 毫秒时间戳 / 秒时间戳
 * @returns {string} 例如 "2026-07-18 21:25:00"，无效输入返回 "-"
 */
export function formatBeijingTime(value) {
  if (value == null || value === '') return '-';

  let date;
  if (typeof value === 'number') {
    // 秒级时间戳（10 位）→ 转毫秒
    date = new Date(value < 1e12 ? value * 1000 : value);
  } else {
    date = new Date(value);
  }

  if (isNaN(date.getTime())) return String(value);
  return BJ_FMT.format(date);
}

export default formatBeijingTime;
