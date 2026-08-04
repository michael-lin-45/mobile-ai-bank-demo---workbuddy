/**
 * qa-smoke.mjs — 可观测 V24 二次对齐 真实浏览器冒烟验证（最终版 v2）
 *
 * 三层证据：① 构建(已在外) ② 单测(已在外) ③ 本脚本 Playwright 真实浏览器冒烟。
 *
 * 双模式对比（隔离「源码 Bug」与「测试脚本 Bug」）：
 *   - REAL 模式：后端 9090 在线，走真实数据（当前运行态，直击主人投诉「效果太差」）。
 *   - FALLBACK 模式：/ai/insights/actions 做 route.abort → 前端走 mock 兜底（验证深链逻辑）；
 *     /api/v1/invocations* 以空数组 [] fulfill → demo 态（验证 RAG 子面板在 demo 路径正常渲染）。
 *
 * 覆盖：
 *   T01 大屏 Zone 徽标/MiniSpark(==15)/ZoneB 错误率 badge/ZoneE 配色
 *   T02 /insight TOP5 深链（真实 vs 兜底）+ Agent P95 1500ms + 慢会话表
 *   T03 /accuracy 混淆矩阵（Cramér's V 卡 / Top-3 含 ↔ 对 / 对角绿-截图 / 只百分比 / 发散色）
 *   T04 /insight→外部调用→RAG 检索质量子面板（5 子指标 + RagQualityChart + DEMO 角标）
 *   T06 DEMO v24 HTML（file://）混淆矩阵同算法验证
 */

import { chromium } from 'playwright';
import { pathToFileURL, fileURLToPath } from 'url';
import { dirname, resolve } from 'path';
import { mkdirSync } from 'fs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const FRONTEND = __dirname;
const BASE = 'http://localhost:3000';
const SHOTS = '/tmp/qa-shots';
mkdirSync(SHOTS, { recursive: true });

const DEMO_HTML = resolve(FRONTEND, '../../docs/specs/可观测DEMO-v24-WorkBuddy.html');
const LOCAL_ECHARTS = resolve(FRONTEND, 'node_modules/echarts/dist/echarts.min.js');

// 真实形状指标（不含 systemErrorRate → 触发 Zone B「错误率 0.12% (待接入)」）
const MOCK_METRICS = {
  dau: 1284, activeSessionsDelta: 12, activeSessionsDeltaUp: true, realTimeOnline: 312,
  requestCount: 540000, requestCountDelta: 8, requestCountDeltaUp: true,
  agentCallCount: 24600, agentCallDelta: 5, agentCallDeltaUp: true,
  l0Calls: 18000, l1Calls: 5200, l2Calls: 1400,
  tokenInput: 1200000, tokenOutput: 800000, tokenDelta: 6, tokenDeltaUp: true,
  ttftP95: 465, ttftP50: 320, ttftP99: 520, ttftDelta: 3, ttftDeltaUp: false,
  p95Latency: 340, p50Latency: 210, p99Latency: 420, latencyDelta: 5, latencyDeltaUp: false,
  intentAccuracy: 94.2, intentAccuracyL0: 95.0, intentAccuracyL1: 91.5,
  rewriteAccuracyL1: 91.5, rewriteAccuracy: 91.5, rerouteRate: 14.2, completionRate: 87.6,
};

const results = [];
let fbInvReqs = []; // 记录 fallback 模式下 invocations 请求 URL，用于诊断拦截是否生效
let fbAllReqs = []; // 记录 fallback 模式下全部请求 URL
const record = (id, name, pass, detail) => {
  results.push({ id, name, pass, detail });
  console.log(`${pass ? '✅' : '❌'} [${id}] ${name} — ${detail}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 进入某左侧导航 */
async function navTo(page, label, route) {
  await page.locator('.ant-menu-item', { hasText: label }).first().click();
  await sleep(900);
  return page.url().includes(route);
}

/** T01 大屏 Zone 断言 */
async function checkT01(page) {
  await navTo(page, '总览大屏', '/dashboard');
  await sleep(1500);

  const badges = await page.evaluate(() => {
    const out = {};
    document.querySelectorAll('span').forEach((s) => {
      const t = (s.textContent || '').trim();
      if (['A', 'B', 'C', 'D', 'E'].includes(t)) {
        const r = getComputedStyle(s);
        if (r.borderTopLeftRadius === '999px' || r.borderRadius === '999px') out[t] = s.style.background || r.backgroundColor;
      }
    });
    return out;
  });
  const badgeOk = ['A', 'B', 'C', 'D', 'E'].every((l) => !!badges[l]);
  record('T01', 'Zone A/B/C/D/E 字母徽标(胶囊) 均显示', badgeOk, `found=${JSON.stringify(badges)}`);

  const miniCount = await page.evaluate(() => Array.from(document.querySelectorAll('canvas')).filter((c) => {
    let el = c.parentElement;
    while (el) {
      const st = el.getAttribute('style') || '';
      if (st.includes('height: 32px') || st.includes('height:32px')) return true;
      el = el.parentElement;
    }
    return false;
  }).length);
  record('T01', 'MiniSpark canvas 总数 == 15', miniCount === 15, `count=${miniCount}`);

  const dashText = await page.locator('.ant-layout-content').innerText();
  // 反馈1：Zone B 错误率 badge 已删除 → 断言「错误率」「0.12」字样不再出现（过期断言自修，不动业务源码）
  const zoneBBadgeAbsent = !dashText.includes('错误率') && !dashText.includes('0.12');
  record('T01', 'Zone B 错误率 badge 已删除（不含「错误率」与「0.12」字样）', zoneBBadgeAbsent,
    `含错误率=${dashText.includes('错误率')} 含0.12=${dashText.includes('0.12')} 含待接入=${dashText.includes('待接入')}`);

  // Zone E：找字母徽标 E → 区头(parent) → ZoneE 外层(parent) → 第2子节点(grid) → 三卡 delta
  const zoneE = await page.evaluate(() => {
    const badgesE = Array.from(document.querySelectorAll('span')).filter((s) => {
      const t = (s.textContent || '').trim();
      const r = getComputedStyle(s);
      return t === 'E' && (r.borderRadius === '999px' || r.borderTopLeftRadius === '999px');
    });
    if (!badgesE.length) return null;
    const outer = badgesE[0].parentElement.parentElement;
    const grid = outer.children[1];
    if (!grid) return null;
    const deltas = [];
    Array.from(grid.children).forEach((card) => {
      const d = Array.from(card.querySelectorAll('div')).find(
        (x) => /^[↓↑→]/.test((x.textContent || '').trim()) && x.style.color,
      );
      if (d) deltas.push({ text: d.textContent.trim(), color: d.style.color });
    });
    return deltas;
  });
  const norm = (c) => (c || '').replace(/\s/g, '');
  const hasRed = zoneE && zoneE.some((d) => norm(d.color) === 'rgb(255,77,79)' || d.color === '#ff4d4f');
  const hasGreen = zoneE && zoneE.some((d) => norm(d.color) === 'rgb(82,196,26)' || d.color === '#52c41a');
  const hasGray = zoneE && zoneE.some((d) => norm(d.color) === 'rgba(0,0,0,0.45)' || d.color === 'rgba(0,0,0,.45)');
  record('T01', 'Zone E 环比配色语义（3 卡：绿/绿/灰，无↑红）',
    Array.isArray(zoneE) && zoneE.length === 3 && !hasRed && hasGreen && hasGray, `deltas=${JSON.stringify(zoneE)}`);

  await page.screenshot({ path: `${SHOTS}/01-dashboard.png`, fullPage: true });
}

/** T02 诊断摘要 4 卡 + TOP5 深链 + Agent P95 + 慢会话。 */
async function checkT02(page, mode) {
  await navTo(page, 'AI 洞察', '/insight');
  await sleep(1800);
  if (mode === 'real') await page.screenshot({ path: `${SHOTS}/02-insight.png`, fullPage: true });

  const insightText0 = await page.locator('.ant-layout-content').innerText();
  const summary4 = ['诊断摘要', '性能瓶颈', '准确率风险', '转化流失', '满意度'].every((t) => insightText0.includes(t));
  record('T02', '诊断摘要 4 卡非空（性能/准确率/转化/满意度）', summary4, `4卡=${summary4}`);

  // 慢会话表（在点击深链跳转前，仍停留在诊断 TAB 时校验）
  const slowTable = insightText0.includes('慢会话根因表');
  record('T02', '慢会话根因表正常渲染', slowTable, `slowTable=${slowTable}`);

  const anchors = await page.$$eval('a', (as) => as.map((a) => a.textContent.trim()).filter((t) => /去.+→/.test(t)));
  const distinct = [...new Set(anchors)];
  const hasVariety = distinct.some((l) => !l.includes('智能诊断'));
  record('T02', `[${mode}] TOP5 深链标签多样（可跳到对应 TAB）`, hasVariety, `anchors=${JSON.stringify(anchors)}`);

  let deepOk = false;
  try {
    await page.locator('a', { hasText: /去准确率/ }).first().click({ timeout: 4000 });
    await sleep(1200);
    const activeTab = (await page.locator('.ant-tabs-tab-active').innerText().catch(() => '')) || '';
    deepOk = activeTab.includes('准确率分析');
    record('T02', `[${mode}] 点击「去准确率 →」跳转到 准确率分析 TAB`, deepOk, `activeTab="${activeTab.trim()}"`);
  } catch (e) {
    record('T02', `[${mode}] 点击「去准确率 →」跳转到 准确率分析 TAB`, false, `异常(锚不存在或跳转失败): ${e.message}`);
  }

  const agentPerf = insightText0.includes('Agent P95 时延 vs 错误率（红线 1500ms）')
    || (await page.locator('.ant-layout-content').innerText()).includes('Agent P95');
  record('T02', 'Agent P95 柱状 + 1500ms 红线标题存在', agentPerf, `text=${agentPerf}`);
}

/** T03 准确率分析混淆矩阵 */
async function checkT03(page) {
  await page.locator('.ant-tabs-tab', { hasText: '准确率分析' }).first().click();
  await sleep(2000);
  await page.screenshot({ path: `${SHOTS}/03-accuracy.png`, fullPage: true });

  const accText = await page.locator('.ant-layout-content').innerText();
  const cramers = /Cramér/.test(accText) && /(健康|关注|混淆严重)/.test(accText);
  record('T03', 'Cramér\'s V 摘要卡（健康灯文字）', cramers,
    `含Cramér=${/Cramér/.test(accText)} 含健康灯=${/(健康|关注|混淆严重)/.test(accText)}`);

  // Top-3：存在「Top-3 混淆对」标题 + 至少一个 ↔ 分隔的混淆对（兼容 ↔ 两侧空白/换行）
  const pairMatch = accText.match(/([^\s↔]+)\s*↔\s*([^\s↔]+)/);
  const actualPair = pairMatch ? `${pairMatch[1]} ↔ ${pairMatch[2]}` : '(none)';
  const top3 = accText.includes('Top-3 混淆对') && !!pairMatch;
  record('T03', 'Top-3 混淆对列表（含 ↔ 对，兼容空白）', top3,
    `含Top-3=${accText.includes('Top-3 混淆对')} 首对=${actualPair}`);

  // 标签语义一致性：DEMO/PRD 期望中文意图标签（理财咨询↔理财解读）；真实后端返回英文码（TRANSFER↔BILL_QUERY）
  const hasCnLabel = accText.includes('理财') || accText.includes('转账') || accText.includes('账单');
  const hasEnLabel = /TRANSFER|BILL_QUERY|WEALTH_CONSULT|WEALTH_INTERPRET/.test(accText);
  record('T03', '混淆对标签为中文（与 DEMO/PRD 对齐，非英文意图码）', hasCnLabel,
    `含中文意图标签=${hasCnLabel} 含英文码=${hasEnLabel}（真实后端返回英文码，缺中文映射 → 源码/设计缺口）`);

  const hasHeatmapCanvas = await page.locator('.ant-layout-content canvas').count();
  record('T03', '混淆矩阵热力图 canvas 已渲染', hasHeatmapCanvas > 0, `canvasCount=${hasHeatmapCanvas}`);
}

/** T04 RAG 检索质量子面板。real 模式预期崩溃；fallback 模式(demo 空数据)预期正常。 */
async function checkT04(page, mode, pageErrors) {
  await navTo(page, 'AI 洞察', '/insight'); // 干净回到洞察页
  await sleep(1200);
  await page.locator('.ant-tabs-tab', { hasText: '外部调用' }).first().click();
  await sleep(1500);
  await page.locator('.ant-segmented-item', { hasText: 'RAG' }).first().click();
  await sleep(2200);
  if (mode === 'real') await page.screenshot({ path: `${SHOTS}/04-rag.png`, fullPage: true });
  else await page.screenshot({ path: `${SHOTS}/04-rag-fallback.png`, fullPage: true });

  const ragText = await page.locator('.ant-layout-content').innerText();
  const crashed = ragText.includes('该 TAB 加载失败') || pageErrors.some((e) => e.includes('loading is not defined'));

  if (mode === 'real') {
    record('T04', '[real] RAG 检索质量子面板正常渲染（无崩溃）', !crashed,
      `crashed=${crashed} pageErrorLoading=${pageErrors.some((e) => e.includes('loading is not defined'))}`);
  } else {
    const ragKpis = ['重排时延 P95', '平均召回文档数', 'RAG 触发率', '检索错误率', '重排错误率'].every((t) => ragText.includes(t));
    const ragPanel = ragText.includes('RAG 检索质量') && ragText.includes('Top-K 相关性分布') && ragText.includes('DEMO') && ragKpis;
    record('T04', '[fallback] RAG 检索质量子面板（5 子指标 KPI + RagQualityChart + DEMO 角标）', ragPanel,
      `5KPI=${ragKpis} 含RAG检索质量=${ragText.includes('RAG 检索质量')} 含TopK=${ragText.includes('Top-K 相关性分布')} 含DEMO=${ragText.includes('DEMO')} crashed=${crashed}`);
    if (crashed) {
      const selSeg = await page.$$eval('.ant-segmented-item-selected', (els) => els.map((e) => e.textContent)).catch(() => []);
      console.log('[FALLBACK][T04-DIAG] selected segment:', JSON.stringify(selSeg));
      console.log('[FALLBACK][T04-DIAG] invocations requests seen:', JSON.stringify(fbInvReqs));
      console.log('[FALLBACK][T04-DIAG] ALL requests seen:', JSON.stringify(fbAllReqs));
      console.log('[FALLBACK][T04-DIAG] ragText snippet:', ragText.slice(0, 300).replace(/\n/g, ' '));
    }
    const ragCanvas = await page.locator('.ant-layout-content canvas').count();
    record('T04', '[fallback] RagQualityChart canvas 渲染', ragCanvas >= 2, `canvasCount=${ragCanvas}`);
  }
}

/** T06 DEMO v24 HTML（file://） */
async function checkT06(browser) {
  const demoErrors = [];
  const dctx = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const dpage = await dctx.newPage();
  dpage.on('console', (m) => { if (m.type() === 'error') demoErrors.push(m.text()); });
  dpage.on('pageerror', (e) => demoErrors.push('PAGEERROR: ' + e.message));
  await dpage.route('**/echarts@5.4.3/dist/echarts.min.js', (route) =>
    route.fulfill({ status: 200, contentType: 'application/javascript', path: LOCAL_ECHARTS }));
  await dpage.route('**://fonts.googleapis.com/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/css', body: '' }));

  await dpage.goto(pathToFileURL(DEMO_HTML).href, { waitUntil: 'domcontentloaded' });
  await sleep(2000);

  const demoNavOk = await dpage.evaluate(() => {
    const item = Array.from(document.querySelectorAll('.nav-item')).find((n) => n.textContent.includes('AI 洞察'));
    if (!item) return { ok: false, reason: 'no nav item' };
    item.click();
    const active = document.querySelector('.nav-item.active');
    const pageActive = document.querySelector('#p-insight')?.classList.contains('active');
    return { ok: !!active && active.textContent.includes('AI 洞察') && !!pageActive, reason: `pageActive=${pageActive}` };
  });
  record('T06', 'DEMO 左侧导航切换（点击 AI 洞察 → 激活）', demoNavOk.ok, demoNavOk.reason);

  await dpage.waitForSelector('#chart-confusion canvas', { timeout: 8000 }).catch(() => {});
  await sleep(800);
  await dpage.screenshot({ path: `${SHOTS}/05-demo-confusion.png`, fullPage: true });

  const demoCramers = await dpage.evaluate(() => {
    const el = document.getElementById('confusion-cramers');
    return el ? el.innerText : '';
  });
  record('T06', 'DEMO Cramér\'s V 卡（健康灯）', /Cramér/.test(demoCramers) && /(健康|关注|混淆严重)/.test(demoCramers),
    `text="${demoCramers.replace(/\n/g, ' ').slice(0, 60)}"`);

  const demoTop3 = await dpage.evaluate(() => {
    const el = document.getElementById('confusion-top3');
    return el ? el.innerText : '';
  });
  record('T06', 'DEMO Top-3 混淆对', demoTop3.includes('Top-3') && demoTop3.includes('理财咨询↔理财解读'),
    `含Top-3=${demoTop3.includes('Top-3')} 含理财咨询↔理财解读=${demoTop3.includes('理财咨询↔理财解读')}`);

  const demoHeat = await dpage.evaluate(() => {
    const inst = window.echarts ? window.echarts.getInstanceByDom(document.getElementById('chart-confusion')) : null;
    if (!inst) return { ok: false, reason: 'no echarts instance' };
    const opt = inst.getOption();
    const s = opt.series && opt.series[0];
    if (!s || s.type !== 'heatmap') return { ok: false, reason: 'not heatmap' };
    let diagonalGreen = true, nonDiagDiverge = true, onlyPct = true;
    s.data.forEach((d) => {
      const [x, y, pct] = d.value;
      const txt = typeof d.label.formatter === 'function' ? d.label.formatter({ value: [x, y, pct], data: d }) : d.label.formatter;
      if (!/^\d+(\.\d+)?$/.test(String(txt).trim())) onlyPct = false;
      if (x === y) { if (d.itemStyle.color !== '#237804') diagonalGreen = false; }
      else if (d.itemStyle.color === '#237804') nonDiagDiverge = false;
    });
    const xName = (opt.xAxis && opt.xAxis[0] && opt.xAxis[0].name) || '';
    const yName = (opt.yAxis && opt.yAxis[0] && opt.yAxis[0].name) || '';
    return { ok: diagonalGreen && nonDiagDiverge && onlyPct && xName.includes('预测意图') && yName.includes('实际意图'), diagonalGreen, nonDiagDiverge, onlyPct, xName, yName };
  });
  record('T06', 'DEMO 混淆矩阵(对角绿#237804/只百分比/发散色/轴标签)', demoHeat.ok, JSON.stringify(demoHeat).slice(0, 160));
  record('T06', 'DEMO 无 console 报错', demoErrors.length === 0, `errors=${demoErrors.slice(0, 3).join(' | ') || 'none'}`);
  await dctx.close();
}

async function main() {
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });

  // ───── REAL 模式（后端 9090 在线，真实数据）─────
  {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    const page = await ctx.newPage();
    const consoleErrors = [];
    const pageErrors = [];
    page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
    page.on('pageerror', (e) => pageErrors.push(e.message));
    await page.route('**/api/v1/metrics/realtime', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, data: MOCK_METRICS }) }));

    await page.goto(BASE, { waitUntil: 'domcontentloaded' });
    await sleep(1200);

    const navItems = [
      ['总览大屏', '/dashboard'], ['会话回放', '/session'], ['链路追踪', '/trace'],
      ['AI 洞察', '/insight'], ['日志查询', '/logs'], ['告警规则', '/alerts'], ['系统设置', '/settings'],
    ];
    let navAllPass = true;
    for (const [label, route] of navItems) {
      const ok = await navTo(page, label, route);
      const content = (await page.locator('.ant-layout-content').innerText().catch(() => '')) || '';
      const white = content.includes('页面渲染失败');
      const pass = ok && !white && content.trim().length > 30;
      if (!pass) navAllPass = false;
      record('T-nav', `导航 ${label} → ${route}${pass ? '' : ' (FAIL)'}`, pass, `urlOk=${ok} whiteScreen=${white} len=${content.trim().length}`);
    }
    record('T-nav', '7 个左侧导航均正常（无整页白屏）', navAllPass, `navAllPass=${navAllPass}`);

    await checkT01(page);
    await checkT02(page, 'real');
    await checkT03(page);
    await checkT04(page, 'real', pageErrors);
    console.log(`[REAL] console.error=${consoleErrors.length} pageerror=${pageErrors.length} :: ${pageErrors.slice(0, 2).join(' | ')}`);
    await ctx.close();
  }

  // ───── FALLBACK 模式（模拟后端未就绪 → 前端 mock 兜底）─────
  {
    fbInvReqs = [];
    fbAllReqs = [];
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    const page = await ctx.newPage();
    const consoleErrors = [];
    const pageErrors = [];
    page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
    page.on('pageerror', (e) => pageErrors.push(e.message));
    await page.route('**/api/v1/metrics/realtime', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, data: MOCK_METRICS }) }));
    await page.route('**/ai/insights/actions', (route) => route.abort()); // 诊断深链走 mock（带 tab）
    // 用正则匹配，确保 /api/v1/invocations 任意 category 都被拦截至空数组 → demo 态
    page.on('request', (r) => { fbAllReqs.push(r.url()); if (r.url().includes('invocations')) fbInvReqs.push(r.url()); });
    await page.route(/\/api\/v1\/invocations/, (route) => {
      console.log('[FALLBACK] intercept invocations ✓');
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, data: [] }) });
    });

    await page.goto(BASE, { waitUntil: 'domcontentloaded' });
    await sleep(1200);

    await checkT02(page, 'fallback');
    await checkT04(page, 'fallback', pageErrors);
    console.log(`[FALLBACK] console.error=${consoleErrors.length} pageerror=${pageErrors.length} :: ${pageErrors.slice(0, 2).join(' | ')}`);
    await ctx.close();
  }

  await checkT06(browser);
  await browser.close();

  const failed = results.filter((r) => !r.pass);
  console.log('\n================ QA SMOKE SUMMARY ================');
  console.log(`TOTAL=${results.length} PASS=${results.length - failed.length} FAIL=${failed.length}`);
  console.log('截图目录:', SHOTS);
  console.log('==================================================');
  process.exit(failed.length ? 1 : 0);
}

main().catch((e) => {
  console.error('SMOKE SCRIPT FATAL:', e);
  process.exit(2);
});
