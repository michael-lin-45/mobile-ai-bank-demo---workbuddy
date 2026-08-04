/**
 * confusionStats — 混淆矩阵前端纯函数计算。
 *
 * 设计权威：docs/specs/可观测V24-对齐设计-2026-07-25.md §3.1 / §7。
 * 纯函数、零依赖，便于单测；DEMO HTML 内联同算法，保证前后端口径一致。
 *
 * 关键约定：
 * - 归一化口径：行归一化（占「实际意图」比例）。NORMALIZE_AXIS = 'row'。
 *   严禁列归一或原始计数展示（单元格文本 = 行归一化百分比，count 仅进 tooltip）。
 * - Cramér's V：V = sqrt(χ² / (n·(k-1)))，k = min(行数, 列数)，n = ΣΣmatrix。
 * - 健康灯阈值（设计拍板 Q3）：<0.15 绿 / 0.15–0.30 黄 / >0.30 红。
 * - 意图码中文映射（QA 缺陷3）：真实后端 /api/v1/ai/confusion-matrix 返回英文意图码，
 *   前端统一映射为中文，对齐 DEMO/PRD；未知码原样透传，避免崩溃且可溯源。
 */

/**
 * 意图码 → 中文标签（对齐 DEMO/PRD 的「理财咨询↔理财解读、转账↔账单…」）。
 * 仅覆盖已知英文意图码；中文标签或非命中码经 toIntentLabel 原样返回。
 * 若真实后端引入新意图码，在此表追加即可（权威口径以架构师确认为准）。
 */
export const INTENT_CODE_LABELS = {
  // —— 真实后端 /api/v1/ai/confusion-matrix 返回的 8 个英文码（权威，须全部命中）——
  TRANSFER: '转账',
  BILL_QUERY: '账单',
  WEALTH_CONSULT: '理财咨询',
  WEALTH_INTERPRET: '理财解读',
  CHAT: '闲聊',
  UNSUPPORTED: '不支持',
  UNKNOWN: '未知意图',
  WEALTH_PURCHASE: '理财购买',
  // —— 扩展字典（真实码命中后以下留作兼容，无妨）——
  BALANCE_QUERY: '余额查询',
  WEALTH_MANAGE: '理财管理',
  LOAN: '贷款',
  CREDIT_CARD: '信用卡',
  REFUND: '退款',
  COMPLAINT: '投诉',
  CHITCHAT: '闲聊',
  GREETING: '问候',
  OTHERS: '其他',
};

/**
 * 将意图标签（英文码或中文）规范为中文展示标签。
 * @param {string} label
 * @returns {string}
 */
export function toIntentLabel(label) {
  if (!label) return label;
  return INTENT_CODE_LABELS[label] || label;
}

/** 行归一化：返回 0–100 百分比矩阵 rowNorm[i][j] = matrix[i][j] / rowSum(i) * 100 */
export function rowNormalize(matrix) {
  if (!Array.isArray(matrix) || matrix.length === 0) return [];
  return matrix.map((row) => {
    const sum = row.reduce((a, b) => a + (Number(b) || 0), 0) || 1;
    return row.map((v) => +((Number(v) || 0) / sum * 100).toFixed(1));
  });
}

/** 样本总量 n = ΣΣmatrix */
export function totalCount(matrix) {
  if (!Array.isArray(matrix)) return 0;
  return matrix.reduce(
    (acc, row) => acc + (Array.isArray(row) ? row.reduce((a, b) => a + (Number(b) || 0), 0) : 0),
    0,
  );
}

/**
 * Cramér's V = sqrt(χ² / (n·(k-1)))
 *   k = min(rows, cols)
 *   n = ΣΣmatrix
 *   χ² = Σ (O - E)² / E，E = rowSum·colSum / n
 * 返回 [0,1]（浮点误差夹紧）。
 */
export function computeCramersV(matrix) {
  const rows = Array.isArray(matrix) ? matrix.length : 0;
  if (rows === 0) return 0;
  const cols = Array.isArray(matrix[0]) ? matrix[0].length : 0;
  if (cols === 0) return 0;

  const rowSums = matrix.map((r) => r.reduce((a, b) => a + (Number(b) || 0), 0));
  const colSums = [];
  for (let j = 0; j < cols; j += 1) {
    let s = 0;
    for (let i = 0; i < rows; i += 1) s += Number(matrix[i][j]) || 0;
    colSums.push(s);
  }
  const n = rowSums.reduce((a, b) => a + b, 0) || 1;

  let chi2 = 0;
  for (let i = 0; i < rows; i += 1) {
    for (let j = 0; j < cols; j += 1) {
      const o = Number(matrix[i][j]) || 0;
      const e = (rowSums[i] * colSums[j]) / n;
      if (e > 0) chi2 += ((o - e) * (o - e)) / e;
    }
  }

  const k = Math.min(rows, cols);
  if (k <= 1) return 0;
  const denom = n * (k - 1);
  if (denom <= 0) return 0;
  const v = Math.sqrt(chi2 / denom);
  // 理论上 V ∈ [0,1]，夹紧浮点误差
  return Math.max(0, Math.min(1, +v.toFixed(4)));
}

/** Cramér's V 健康灯评级（设计拍板 Q3 阈值） */
export function cramersHealth(v) {
  if (v < 0.15) return { level: 'good', color: '#52c41a', text: '健康' };
  if (v <= 0.30) return { level: 'warn', color: '#faad14', text: '关注' };
  return { level: 'bad', color: '#ff4d4f', text: '混淆严重' };
}

/**
 * 非对角混淆对按占该行比例降序取前 N。
 * @param {string[]} labels  行=实际 / 列=预测 共用标签序
 * @param {number[][]} matrix 原始计数 matrix[actual][predicted]
 * @param {number} [topN=3]
 * @returns {Array<{actual:string, predicted:string, pct:number}>}
 */
export function computeTopConfusionPairs(labels, matrix, topN = 3) {
  if (!Array.isArray(matrix) || !Array.isArray(labels)) return [];
  const pairs = [];
  matrix.forEach((row, i) => {
    const rowSum = row.reduce((a, b) => a + (Number(b) || 0), 0) || 1;
    row.forEach((v, j) => {
      if (i === j) return;
      const count = Number(v) || 0;
      const pct = +((count / rowSum) * 100).toFixed(1);
      pairs.push({ actual: labels[i], predicted: labels[j], pct });
    });
  });
  pairs.sort((a, b) => b.pct - a.pct);
  return pairs.slice(0, topN);
}
