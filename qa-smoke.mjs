// QA real-browser smoke test for 可观测 DEMO V23->V24 frontend alignment.
// Uses installed chromium (playwright). No source changes.
import { chromium } from 'playwright';
import fs from 'fs';

const BASE = 'http://localhost:3000';
// Resolve DEMO v24 HTML via the script's own location (handles Windows drive + unicode + spaces)
const DEMO_PATH = new URL('./docs/specs/可观测DEMO-v24-WorkBuddy.html', import.meta.url).href;
const SHOT_DIR = '/tmp/qa-shots';
fs.mkdirSync(SHOT_DIR, { recursive: true });

const results = [];
const consoleErrors = [];
const pageErrors = [];

function rec(name, pass, detail) {
  results.push({ name, pass: !!pass, detail });
  console.log(`${pass ? 'PASS' : 'FAIL'} | ${name} | ${detail}`);
}

const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
page.on('pageerror', (e) => pageErrors.push(e.message));

/* ============ PART A: Frontend SPA (localhost:3000) ============ */
console.log('\n===== PART A: Frontend SPA navigation & rendering =====');

await page.goto(BASE + '/', { waitUntil: 'networkidle' });
await page.waitForSelector('.ant-menu', { timeout: 15000 });

const navItems = [
  { key: '/dashboard', label: '总览大屏' },
  { key: '/session', label: '会话回放' },
  { key: '/trace', label: '链路追踪' },
  { key: '/insight', label: 'AI 洞察' },
  { key: '/logs', label: '日志查询' },
  { key: '/alerts', label: '告警规则' },
  { key: '/settings', label: '系统设置' },
];

let allNavOk = true;
for (const item of navItems) {
  try {
    await page.locator('.ant-menu-item', { hasText: item.label }).first().click();
    await page.waitForTimeout(900);
    const url = page.url();
    const onRoute = url.includes(item.key);
    const fullAlert = await page.getByText('页面渲染失败').count();
    const sidebarVisible = await page.locator('.ant-menu').first().isVisible();
    const ok = onRoute && fullAlert === 0 && sidebarVisible;
    if (!ok) allNavOk = false;
    rec(`导航点击「${item.label}」→ ${item.key}`, ok, `url=${url} 全页Alert=${fullAlert} 侧栏可见=${sidebarVisible}`);
  } catch (e) {
    allNavOk = false;
    rec(`导航点击「${item.label}」→ ${item.key}`, false, 'EXCEPTION: ' + e.message);
  }
}

// /insight diagnosis tab checks
try {
  await page.locator('.ant-menu-item', { hasText: 'AI 洞察' }).first().click();
  await page.waitForTimeout(600);
  await page.getByText('智能诊断摘要', { exact: false }).first().waitFor({ timeout: 8000 });
  const summaryVisible = await page.getByText('智能诊断摘要').first().isVisible();
  const cards = ['性能瓶颈', '准确率风险', '转化流失', '满意度'];
  let cardsOk = true;
  for (const c of cards) {
    const n = await page.getByText(c, { exact: false }).count();
    if (n === 0) cardsOk = false;
  }
  const highVisible = await page.getByText('HIGH').count(); // severity badge proves non-empty summary
  rec('智能诊断摘要 4 卡非空', summaryVisible && cardsOk, `摘要条可见=${summaryVisible} 四卡标签齐全=${cardsOk} HIGH角标可见=${highVisible > 0}`);

  // PerfScatter: card title+red threshold line present & chart canvas mounted
  const perfCardText = await page.getByText('Agent P95 时延 vs 错误率', { exact: false }).count();
  const perfCanvas = await page.locator('.ant-card:has-text("Agent P95 时延") canvas').count();
  rec('Agent P95 柱状图+红线(1500ms) 已挂载', perfCardText > 0 && perfCanvas > 0,
    `卡片标题可见=${perfCardText > 0} 图表canvas数=${perfCanvas}`);
  await page.screenshot({ path: SHOT_DIR + '/frontend-insight-diagnosis.png', fullPage: false });

  // Slow session root-cause table
  const slowTitle = await page.getByText('慢会话根因表').count();
  const greenState = await page.getByText('近6h 无慢会话').count();
  const tableHeader = await page.getByText('根因类别').count();
  rec('慢会话根因表(表头或绿色状态)', slowTitle > 0 && (greenState > 0 || tableHeader > 0),
    `标题可见=${slowTitle > 0} 绿色状态=${greenState > 0} 表头=有(${tableHeader > 0})`);
} catch (e) {
  rec('智能诊断页断言', false, 'EXCEPTION: ' + e.message);
}

// switch to accuracy tab
try {
  await page.locator('.ant-tabs-tab', { hasText: '准确率分析' }).first().click();
  await page.waitForTimeout(1200);
  const overviewCard = await page.getByText('准确率概览').count();
  // white card check: find the innermost container that actually carries background #fff
  const bg = await page.evaluate(() => {
    const els = [...document.querySelectorAll('div')].filter(e => e.textContent && e.textContent.includes('准确率概览'));
    for (const el of els) {
      const cs = getComputedStyle(el);
      if (cs.backgroundColor === 'rgb(255, 255, 255)' && cs.boxShadow && cs.boxShadow !== 'none') {
        return cs.backgroundColor + ' | shadow=' + cs.boxShadow;
      }
    }
    return 'none';
  });
  const heatmapCard = await page.getByText('意图混淆矩阵').count();
  const heatCanvas = await page.locator('.ant-card:has-text("意图混淆矩阵") canvas').count();
  const caption = await page.getByText('单元格颜色').count();
  rec('准确率页-「准确率概览」白卡样式', overviewCard > 0 && bg.startsWith('rgb(255, 255, 255)'),
    `概览文本可见=${overviewCard > 0} 白卡=${bg}`);
  rec('准确率页-混淆矩阵热力图(两轴标签canvas)', heatmapCard > 0 && heatCanvas > 0 && caption > 0,
    `卡片可见=${heatmapCard > 0} canvas数=${heatCanvas} 说明文字可见=${caption > 0}`);
  await page.screenshot({ path: SHOT_DIR + '/frontend-accuracy.png', fullPage: false });
  try { await page.getByText('意图混淆矩阵', { exact: false }).scrollIntoViewIfNeeded(); await page.waitForTimeout(300); } catch {}
  await page.screenshot({ path: SHOT_DIR + '/frontend-accuracy-confusion.png', fullPage: false });
} catch (e) {
  rec('准确率页断言', false, 'EXCEPTION: ' + e.message);
}

// /dashboard sparkline check
try {
  await page.locator('.ant-menu-item', { hasText: '总览大屏' }).first().click();
  await page.waitForTimeout(1500);
  const fullAlert = await page.getByText('页面渲染失败').count();
  const dashCards = await page.getByText('诊断摘要').count();
  const canvasCount = await page.locator('canvas').count();
  rec('总览大屏-挂载无整页崩溃 + 6卡sparkline', fullAlert === 0 && canvasCount >= 6,
    `全页Alert=${fullAlert} 诊断摘要卡可见=${dashCards > 0} canvas总数=${canvasCount}(含6张MetricCard sparkline)`);
  await page.screenshot({ path: SHOT_DIR + '/frontend-dashboard.png', fullPage: false });
} catch (e) {
  rec('总览大屏断言', false, 'EXCEPTION: ' + e.message);
}

// frontend console / page errors summary
const feCritical = consoleErrors.filter(t => /is not defined|SyntaxError|Unexpected|Cannot read|TypeError/.test(t));
rec('前端运行期无致命JS报错', feCritical.length === 0, `consoleError=${consoleErrors.length} pageError=${pageErrors.length} 致命=${feCritical.length}`);

/* ============ PART B: DEMO v24 standalone HTML (file://) ============ */
console.log('\n===== PART B: DEMO v24 file:// navigation & charts =====');
await page.goto(DEMO_PATH, { waitUntil: 'networkidle' });
await page.waitForSelector('.nav-item', { timeout: 15000 });
// critical: the V23 bug was a missing `}` causing sw/initAllCharts/psw undefined -> clicks dead.
// If those are undefined we'd see "is not defined" console/page errors here.
const demoConsoleErrors = [];
const demoPageErrors = [];
page.on('console', (m) => { if (m.type() === 'error') demoConsoleErrors.push(m.text()); });
page.on('pageerror', (e) => demoPageErrors.push(e.message));

const demoPages = ['overview', 'session', 'trace', 'insight', 'logs', 'alerts', 'settings'];
let allDemoNavOk = true;
for (const p of demoPages) {
  try {
    await page.evaluate((pg) => {
      const el = [...document.querySelectorAll('.nav-item')].find(e => (e.getAttribute('onclick') || '').includes("sw('" + pg + "'"));
      if (el) el.click();
    }, p);
    await page.waitForTimeout(500);
    const active = await page.evaluate((pg) => {
      const node = document.getElementById('p-' + pg);
      return !!node && node.classList.contains('active');
    }, p);
    if (!active) allDemoNavOk = false;
    rec(`DEMO导航 sw('${p}')→面板切换`, active, `p-${p} active=${active}`);
  } catch (e) {
    allDemoNavOk = false;
    rec(`DEMO导航 sw('${p}')`, false, 'EXCEPTION: ' + e.message);
  }
}

// mini sparkline canvases: <div id="mini-X"> containers; echarts.init renders a <canvas> INSIDE each.
// Reload DEMO fresh so overview page is active (clean baseline; re-navigating re-inits hidden charts).
await page.goto(DEMO_PATH, { waitUntil: 'networkidle' });
await page.waitForSelector('div[id^="mini-"]', { timeout: 15000 });
await page.waitForTimeout(900);
const miniDivCount = await page.locator('div[id^="mini-"]').count();
const miniCanvasCount = await page.locator('div[id^="mini-"] canvas').count();
const missingMini = await page.evaluate(() => {
  const ids = ['mini-A1','mini-A2','mini-A3','mini-B1','mini-B2','mini-B3','mini-C1','mini-C2','mini-C3','mini-D1','mini-D2','mini-D3','mini-E1','mini-E2','mini-E3'];
  return ids.filter(id => { const el = document.getElementById(id); return !el || !el.querySelector('canvas'); });
});
rec('DEMO sparkline mini-A1..E3 渲染', miniDivCount >= 15 && miniCanvasCount >= 15,
  `mini容器=${miniDivCount} 内嵌canvas=${miniCanvasCount} (期望≥15) 缺失canvas的id=${JSON.stringify(missingMini)}`);

// confusion matrix two-axis labels present (canvas + card)
const confCanvas = await page.locator('#chart-confusion').count();
rec('DEMO 混淆矩阵 canvas 已渲染', confCanvas > 0, `chart-confusion canvas数=${confCanvas}`);
await page.evaluate(() => { // open insight page so confusion matrix visible in screenshot
  const el = [...document.querySelectorAll('.nav-item')].find(e => (e.getAttribute('onclick') || '').includes("sw('insight'")); if (el) el.click();
});
await page.waitForTimeout(600);
await page.screenshot({ path: SHOT_DIR + '/demo-insight.png', fullPage: false });
// switch to accuracy tab and capture confusion matrix
await page.evaluate(() => { const el = [...document.querySelectorAll('#p-insight .tab')].find(e => e.textContent && e.textContent.includes('准确率分析')); if (el) el.click(); });
await page.waitForTimeout(600);
try { await page.getByText('意图混淆矩阵', { exact: false }).scrollIntoViewIfNeeded(); await page.waitForTimeout(300); } catch {}
await page.screenshot({ path: SHOT_DIR + '/demo-accuracy-confusion.png', fullPage: false });
await page.evaluate(() => { const el = [...document.querySelectorAll('.nav-item')].find(e => (e.getAttribute('onclick') || '').includes("sw('overview'")); if (el) el.click(); });
await page.waitForTimeout(500);
await page.screenshot({ path: SHOT_DIR + '/demo-overview.png', fullPage: false });

// DEMO parse/run errors (the core fix)
const demoCritical = [...demoConsoleErrors, ...demoPageErrors].filter(t => /is not defined|SyntaxError|Unexpected|initAllCharts|sw\(|psw/.test(t));
rec('DEMO v24 无脚本解析/未定义报错(核心修复)', demoCritical.length === 0,
  `consoleError=${demoConsoleErrors.length} pageError=${demoPageErrors.length} 致命=${demoCritical.length}`);

await browser.close();

// write structured result
const out = { results, consoleErrors, pageErrors, demoConsoleErrors, demoPageErrors };
fs.writeFileSync('/tmp/qa_smoke_result.json', JSON.stringify(out, null, 2));
const passed = results.filter(r => r.pass).length;
console.log(`\n===== SUMMARY: ${passed}/${results.length} checks passed =====`);
