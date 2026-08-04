/**
 * qa-rag-round4.mjs — RAG tab V24 真实浏览器冒烟（终裁轮，聚焦 T04）。
 *
 * 与 qa-smoke.mjs 互补：qa-smoke.mjs 覆盖 T01–T06 整体，本脚本在真实 Chromium 中
 * 单独、深度校验团队终裁要求的 T04 RAG 六项证据，并把「全量细节」写入
 * /d/GitHub/mobile-ai-bank-demo - workbuddy/qa-v24-round4.json。
 *
 * 不改动任何业务源码；复用已运行的 :3000 实时 dev server（含工程师 RagPanel 三块结构）。
 *
 * 校验点（对齐 team-lead 终裁要求）：
 *   - 三块结构：运行指标 / 趋势图 / 检索质量子面板
 *   - 质量判定表 5 行且无红色（good→green / warn→gold）
 *   - KPI delta 副文案（5 条）
 *   - 底部 note（运行态/效果态/不重叠）
 *   - 3 块 DemoBadge
 *   - RagQualityChart canvas ≥ 2（趋势图 + Top-K 图）
 */

import { chromium } from 'playwright';
import { pathToFileURL, fileURLToPath } from 'url';
import { dirname, resolve } from 'path';
import { readFileSync, writeFileSync, mkdirSync } from 'fs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const FRONTEND = __dirname;
const BASE = 'http://localhost:3000';
const REPO_ROOT = resolve(FRONTEND, '..', '..'); // .../mobile-ai-bank-demo - workbuddy
const OUT = resolve(REPO_ROOT, 'qa-v24-round4.json');
const SHOTS = '/tmp/qa-shots';
mkdirSync(SHOTS, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const results = [];
const record = (name, pass, detail) => {
  results.push({ name, pass, detail });
  console.log(`${pass ? '✅' : '❌'} ${name} — ${detail}`);
};

async function navTo(page, label, route) {
  await page.locator('.ant-menu-item', { hasText: label }).first().click();
  await sleep(900);
  return page.url().includes(route);
}

async function main() {
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  // 兜底拦截真实接口，保证 demo 态稳定（RagPanel 直接用 getRagMock，不受 invocations 影响）
  await page.route('**/api/v1/metrics/realtime', (r) =>
    r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, data: {} }) }));
  await page.route(/\/api\/v1\/invocations/, (r) =>
    r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, data: [] }) }));

  await page.goto(BASE, { waitUntil: 'domcontentloaded' });
  await sleep(1200);
  await navTo(page, 'AI 洞察', '/insight');
  await sleep(1800);
  await page.locator('.ant-tabs-tab', { hasText: '外部调用' }).first().click();
  await sleep(1500);
  await page.locator('.ant-segmented-item', { hasText: 'RAG' }).first().click();
  await sleep(2600); // 等 echarts canvas 真实渲染

  const rag = await page.evaluate(() => {
    const content = document.querySelector('.ant-layout-content');
    // 仅锁定 RagPanel 根容器：三块 Card 标题的最小公共祖先（避免命中其它常驻 antd Tab pane 的表格）
    const lca = (nodes) => {
      if (!nodes.length) return null;
      const lists = nodes.map((n) => { const p = []; let x = n; while (x) { p.push(x); x = x.parentElement; } return p; });
      for (const el of lists[0]) { if (lists.every((l) => l.includes(el))) return el; }
      return null;
    };
    const heads = Array.from(content.querySelectorAll('.ant-card-head-title')).filter((h) =>
      /RAG 运行指标（5 子指标）/.test(h.textContent) ||
      /趋势图 · 5 子指标（近 6h）/.test(h.textContent) ||
      /RAG 检索质量子面板/.test(h.textContent));
    const ragRoot = heads.length === 3 ? lca(heads) : content;
    const text = ragRoot ? ragRoot.innerText : '';
    const demoSpans = ragRoot ? Array.from(ragRoot.querySelectorAll('span')).filter((s) => (s.textContent || '').trim() === 'DEMO').length : 0;
    const kpis = ['重排时延 P95', '平均召回文档数', 'RAG 触发率', '检索错误率', '重排错误率'].filter((t) => text.includes(t));
    const blockTitles = ['RAG 运行指标（5 子指标）', '趋势图 · 5 子指标（近 6h）', 'RAG 检索质量子面板'].filter((t) => text.includes(t));
    // 质量判定表：表头含「质量维度」的表（其它 pane 的表不计入）
    const qTable = ragRoot ? Array.from(ragRoot.querySelectorAll('.ant-table')).find((t) => {
      const th = t.querySelector('.ant-table-thead'); return th && /质量维度/.test(th.textContent);
    }) : null;
    const qBody = qTable ? qTable.querySelector('.ant-table-tbody') : null;
    const bodyRows = qBody ? Array.from(qBody.querySelectorAll('tr.ant-table-row')) : [];
    const rows = bodyRows.length;
    const dims = bodyRows.map((r) => { const c = r.querySelector('.ant-table-cell'); return c ? c.textContent.trim() : ''; });
    const green = qTable ? qTable.querySelectorAll('.ant-tag-green').length : 0;
    const gold = qTable ? qTable.querySelectorAll('.ant-tag-gold').length : 0;
    const red = qTable ? qTable.querySelectorAll('.ant-tag-red').length : 0;
    const deltas = ['↓ 12.4% 较昨日', '↑ 0.6 篇', '↑ 3.1% 会话含检索', '↓ 0.1% 网络/超时', '→ 持平'].filter((t) => text.includes(t));
    const note = text.includes('运行态监控') && text.includes('效果态评估') && text.includes('二者视角不同、不重叠');
    const canvas = ragRoot ? ragRoot.querySelectorAll('canvas').length : 0;
    return { demoSpans, kpis: kpis.length, blockTitles: blockTitles.length, rows, dims, green, gold, red, deltas: deltas.length, note, canvas, hasPanel: text.includes('RAG 检索质量') };
  });

  record('T04 三块结构（运行指标/趋势图/检索质量子面板）', rag.blockTitles === 3, `blockTitles=${rag.blockTitles}/3`);
  record('T04 质量判定表 5 行', rag.rows === 5, `rows=${rag.rows} dims=${JSON.stringify(rag.dims)}`);
  record('T04 质量表配色 good→green(2)/warn→gold(3)/无红(0)', rag.green === 2 && rag.gold === 3 && rag.red === 0, `green=${rag.green} gold=${rag.gold} red=${rag.red}`);
  record('T04 KPI delta 副文案（5 条均渲染）', rag.deltas === 5, `deltas=${rag.deltas}/5`);
  record('T04 底部 note（运行态/效果态/不重叠）', rag.note, `note=${rag.note}`);
  record('T04 3 块 DemoBadge', rag.demoSpans === 3, `demoSpans=${rag.demoSpans}`);
  record('T04 RagQualityChart canvas ≥ 2（趋势图 + Top-K 图）', rag.canvas >= 2, `canvas=${rag.canvas}`);
  record('T04 RAG 面板真实渲染（无崩溃 / 无 pageerror）', rag.hasPanel && pageErrors.length === 0, `hasPanel=${rag.hasPanel} pageErrors=${pageErrors.length}`);

  await page.screenshot({ path: `${SHOTS}/04-rag-round4.png`, fullPage: true });

  // 合并 qa-smoke.mjs 整体结论（若已运行并落盘 qa-smoke.out）
  let smoke = { total: null, pass: null, fail: null, t04: [] };
  try {
    const out = readFileSync(resolve(FRONTEND, 'qa-smoke.out'), 'utf8');
    const m = out.match(/TOTAL=(\d+)\s+PASS=(\d+)\s+FAIL=(\d+)/);
    if (m) smoke = { total: +m[1], pass: +m[2], fail: +m[3], t04: out.split('\n').filter((l) => l.includes('[T04]')).map((l) => l.trim()) };
  } catch { /* qa-smoke.out 可选 */ }

  const failed = results.filter((r) => !r.pass);
  const summary = { total: results.length, pass: results.length - failed.length, fail: failed.length };
  const payload = {
    generatedAt: new Date().toISOString(),
    mode: 'playwright chromium (headless) vs live :3000 vite dev server',
    smokeSummary: smoke,
    t04Rag: results,
    summary,
    pageErrors,
  };
  writeFileSync(OUT, JSON.stringify(payload, null, 2));
  console.log(`\nROUND4 TOTAL=${summary.total} PASS=${summary.pass} FAIL=${summary.fail} → ${OUT}`);
  await browser.close();
  process.exit(summary.fail ? 1 : 0);
}

main().catch((e) => {
  console.error('ROUND4 FATAL:', e);
  process.exit(2);
});
