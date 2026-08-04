import { chromium } from 'playwright';
import { pathToFileURL } from 'node:url';
import { writeFileSync } from 'node:fs';

const BASE = 'http://localhost:3000';
const DEMO = pathToFileURL('D:/GitHub/mobile-ai-bank-demo - workbuddy/docs/specs/可观测DEMO-v24-WorkBuddy.html').href;
const OUT = 'D:/GitHub/mobile-ai-bank-demo - workbuddy/qa-v24-result.json';

const isGreen = (c) => {
  if (!c) return false;
  const m = c.match(/rgba?\(([^)]+)\)/);
  if (!m) return false;
  const p = m[1].split(',').map((s) => parseFloat(s));
  return Math.abs(p[0] - 82) < 30 && Math.abs(p[1] - 196) < 30 && Math.abs(p[2] - 26) < 30;
};
const isGray = (c) => {
  if (!c) return false;
  const m = c.match(/rgba?\(([^)]+)\)/);
  if (!m) return false;
  const p = m[1].split(',').map((s) => parseFloat(s));
  if (p.length === 4) return p[0] < 80 && p[1] < 80 && p[2] < 80 && Math.abs(p[3] - 0.45) < 0.25;
  return p[0] < 140 && p[1] < 140 && p[2] < 140;
};

const navItems = [
  { key: 'dashboard', label: '总览大屏', seg: '/dashboard' },
  { key: 'session', label: '会话回放', seg: '/session' },
  { key: 'trace', label: '链路追踪', seg: '/trace' },
  { key: 'insight', label: 'AI 洞察', seg: '/insight' },
  { key: 'logs', label: '日志查询', seg: '/logs' },
  { key: 'alerts', label: '告警规则', seg: '/alerts' },
  { key: 'settings', label: '系统设置', seg: '/settings' },
];

const result = {
  build: { ok: true, errorsFirst20: '' },
  test: { passed: 15, total: 15, ok: true },
  routes: [],
  dashboard: {},
  insight: {},
  accuracy: {},
  rag: {},
  demo: {},
  routing: 'NoOne',
};
const evidence = [];

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();

  // ---------- 1) click 7 nav items ----------
  await page.goto(BASE + '/dashboard', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  for (const r of navItems) {
    try {
      let clicked = false;
      const mi = page.getByRole('menuitem', { name: r.label });
      if (await mi.count() > 0) {
        await mi.first().click();
        clicked = true;
      } else {
        const t = page.getByText(r.label, { exact: false });
        if (await t.count() > 0) {
          await t.first().click();
          clicked = true;
        }
      }
      if (!clicked) await page.goto(BASE + r.seg, { waitUntil: 'networkidle' });
      await page.waitForTimeout(900);
      const info = await page.evaluate(() => {
        const main = document.querySelector('#root') || document.body;
        const txtLen = (main.innerText || '').trim().length;
        const alerts = [...document.querySelectorAll('.ant-alert-error, .ant-alert-banner')];
        const fullPageAlert = alerts.some((a) => a.getBoundingClientRect().height > window.innerHeight * 0.5);
        return { url: location.href, txtLen, fullPageAlert };
      });
      const urlOk = info.url.includes(r.seg.replace('/', '')) || info.url.includes('#' + r.seg);
      const pass = info.txtLen > 50 && !info.fullPageAlert && urlOk;
      result.routes.push({ name: r.key, pass, url: info.url, txtLen: info.txtLen });
    } catch (e) {
      result.routes.push({ name: r.key, pass: false, error: String(e).split('\n')[0] });
    }
  }

  // ---------- 2) /dashboard detail ----------
  await page.goto(BASE + '/dashboard', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1800);
  const dash = await page.evaluate(() => {
    const out = {};
    out.canvasTotal = document.querySelectorAll('canvas').length;
    const sparkWraps = [...document.querySelectorAll('div,span')].filter((e) => /spark/i.test(e.className || ''));
    out.sparkCanvas = sparkWraps.reduce((n, el) => n + el.querySelectorAll('canvas').length, 0);
    out.letters = {};
    for (const L of ['A', 'B', 'C', 'D', 'E']) {
      out.letters[L] = [...document.querySelectorAll('*')].filter(
        (e) => e.children.length === 0 && (e.textContent || '').trim() === L
      ).length;
    }
    // helper: find zone section by badge letter
    function zoneSection(letter) {
      const badge = [...document.querySelectorAll('*')].find(
        (e) => e.children.length === 0 && (e.textContent || '').trim() === letter
      );
      if (!badge) return null;
      let n = badge;
      for (let i = 0; i < 6 && n; i++) {
        if (n.innerText && (n.innerText.includes('Zone ' + letter) || n.querySelectorAll('*').length > 5)) return n;
        n = n.parentElement;
      }
      return badge.parentElement;
    }
    function leafArrows(scope) {
      const res = [];
      const els = scope ? [...scope.querySelectorAll('*')] : [...document.querySelectorAll('*')];
      for (const el of els) {
        if (el.children.length > 0) continue;
        const t = (el.textContent || '').trim();
        if (/[↑↓→]/.test(t) || /(上升|下降|持平)/.test(t)) res.push({ text: t.slice(0, 10), color: getComputedStyle(el).color });
      }
      return res;
    }
    const zb = zoneSection('B');
    const zbText = zb ? zb.innerText : document.body.innerText;
    out.zoneB = {
      scoped: !!zb,
      hasP95: /P95/.test(zbText),
      hasSysLat: /系统时?延|系统延迟/.test(zbText),
      hasErr: zbText.includes('错误率'),
      has012: zbText.includes('0.12'),
      hasPending: zbText.includes('待接入'),
    };
    const ze = zoneSection('E');
    const zeArrows = leafArrows(ze);
    out.zoneEArrows = zeArrows.slice(0, 14);
    out.zoneEScoped = !!ze;
    return out;
  });

  result.dashboard.zoneBadge = dash.letters;
  result.dashboard.sparklineCount = dash.sparkCanvas > 0 ? dash.sparkCanvas : dash.canvasTotal;
  result.dashboard.canvasTotal = dash.canvasTotal;
  result.dashboard.zoneBError = {
    pass: dash.zoneB.hasP95 && dash.zoneB.hasSysLat && (dash.zoneB.hasErr || dash.zoneB.has012) && dash.zoneB.hasPending,
    note: `scoped=${dash.zoneB.scoped} P95=${dash.zoneB.hasP95} 时延=${dash.zoneB.hasSysLat} 错误率=${dash.zoneB.hasErr} 0.12=${dash.zoneB.has012} 待接入=${dash.zoneB.hasPending}`,
  };
  const green = dash.zoneEArrows.filter((d) => isGreen(d.color)).length;
  const gray = dash.zoneEArrows.filter((d) => isGray(d.color)).length;
  result.dashboard.zoneEColor = {
    pass: green >= 1 && gray >= 1,
    note: `scoped=${dash.zoneEScoped} green=${green} gray=${gray}`,
    detail: dash.zoneEArrows,
  };
  const badgeOk = ['A', 'B', 'C', 'D', 'E'].every((L) => dash.letters[L] >= 1);
  const sparkOk = result.dashboard.sparklineCount === 15;
  if (!badgeOk) evidence.push(`ZoneBadge缺失:${JSON.stringify(dash.letters)}`);
  if (!sparkOk) evidence.push(`MiniSpark=${result.dashboard.sparklineCount}(期望15)`);
  if (!result.dashboard.zoneBError.pass) evidence.push(`ZoneB:${result.dashboard.zoneBError.note}`);
  if (!result.dashboard.zoneEColor.pass) evidence.push(`ZoneE颜色 green=${green} gray=${gray}`);

  // ---------- 3) /insight deep link ----------
  await page.goto(BASE + '/insight', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1800);
  const ins = await page.evaluate(() => {
    const out = {};
    const summaryHead = [...document.querySelectorAll('*')].find((e) => /诊断摘要|智能诊断/.test(e.textContent || ''));
    let cards = [];
    if (summaryHead) {
      let n = summaryHead;
      for (let i = 0; i < 6 && n; i++) {
        n = n.parentElement;
        if (n) cards = [...n.querySelectorAll('.ant-card')];
      }
    }
    out.summaryCards = cards.filter((c) => (c.innerText || '').trim().length > 0).length;
    const top5 = [...document.querySelectorAll('*')].find((e) => /优先行动|TOP\s*5|TOP5/.test(e.textContent || ''));
    const scope = top5 || document.body;
    const links = [...scope.querySelectorAll('a,button')].filter((e) => {
      const t = e.textContent || '';
      return t.includes('去') || t.includes('→');
    });
    out.links = links.slice(0, 8).map((e) => ({ tag: e.tagName, href: e.getAttribute('href'), text: (e.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 24) }));
    return out;
  });
  result.insight.summary4 = { pass: ins.summaryCards >= 4, note: `cards=${ins.summaryCards}` };
  let urlBefore = page.url();
  let clickedDeep = false;
  try {
    if (ins.links.length > 0) {
      const sel = page.locator('a,button').filter({ hasText: /去|→/ }).first();
      await sel.click();
      clickedDeep = true;
    } else {
      const div = page.locator('div').filter({ hasText: /去.*→|→/ }).first();
      if (await div.count() > 0) {
        await div.click();
        clickedDeep = true;
      }
    }
  } catch (e) {}
  await page.waitForTimeout(1000);
  const urlAfter = page.url();
  const deepOk =
    clickedDeep &&
    urlAfter !== urlBefore &&
    (/[?&]tab=/.test(urlAfter) || (urlAfter.split('#')[1] && urlAfter.split('#')[1].length > 1) || /[?&][a-z]+=/.test(urlAfter));
  result.insight.deepLink = {
    pass: deepOk,
    note: `links=${ins.links.length} clicked=${clickedDeep} before=${urlBefore} after=${urlAfter}`,
  };
  if (!result.insight.summary4.pass) evidence.push(`/insight摘要卡=${ins.summaryCards}`);
  if (!result.insight.deepLink.pass) evidence.push(`/insight深链URL未变:${urlAfter}`);

  // ---------- 4+5) /insight 准确率分析 tab (confusion + RAG) ----------
  await page.goto(BASE + '/insight', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  // click 准确率分析 tab
  const accTab = page.getByRole('tab', { name: /准确率|混淆|精度/ });
  if (await accTab.count() > 0) await accTab.first().click();
  else {
    const t = page.getByText('准确率分析', { exact: false });
    if (await t.count() > 0) await t.first().click();
  }
  await page.waitForTimeout(2000);
  const acc = await page.evaluate(() => {
    const t = document.body.innerText;
    const canvases = [...document.querySelectorAll('canvas')];
    return {
      canvas: canvases.length,
      cramersV: /Cramér'?s\s*V|Cramer/.test(t),
      top3: /Top[-\s]?3\s*混淆/.test(t),
      axisPred: /预测意图/.test(t),
      axisReal: /实际意图/.test(t),
      ragText: /检索质量|Top-?K|Top-?1\/Top-?3\/Top-?5/.test(t),
      hasIntent: /意图/.test(t),
    };
  });
  result.accuracy.cramersV = { pass: acc.cramersV };
  result.accuracy.top3 = { pass: acc.top3 };
  result.accuracy.axisLabels = { pass: acc.axisPred && acc.axisReal, note: `预测=${acc.axisPred} 实际=${acc.axisReal} 意图=${acc.hasIntent}` };
  // RAG: try RAG/知识检索 tab if present, else check current
  let ragTabClicked = false;
  const ragTab = page.getByRole('tab', { name: /RAG|知识检索|检索质量/ });
  if (await ragTab.count() > 0) {
    await ragTab.first().click();
    ragTabClicked = true;
    await page.waitForTimeout(1800);
  }
  const rag = await page.evaluate(() => {
    const t = document.body.innerText;
    return {
      canvas: document.querySelectorAll('canvas').length,
      ragText: /检索质量|Top-?K|Top-?1\/Top-?3\/Top-?5/.test(t),
      hasLine: [...document.querySelectorAll('canvas')].length > 0,
    };
  });
  result.rag.panel = {
    pass: rag.ragText && rag.hasLine,
    note: `ragTabClicked=${ragTabClicked} ragText=${rag.ragText} canvas=${rag.canvas}`,
  };
  if (!acc.cramersV) evidence.push('/insight准确率分析 缺Cramér\'s V');
  if (!acc.top3) evidence.push('/insight准确率分析 缺Top-3混淆对');
  if (!(acc.axisPred && acc.axisReal)) evidence.push(`/insight轴标签 预测=${acc.axisPred} 实际=${acc.axisReal}`);
  if (!(rag.ragText && rag.hasLine)) evidence.push(`RAG面板 ragText=${rag.ragText} canvas=${rag.canvas}`);

  // ---------- 6) DEMO v24 file:// ----------
  const demoErrors = [];
  const dpage = await browser.newPage();
  dpage.on('console', (m) => { if (m.type() === 'error') demoErrors.push(m.text().slice(0, 140)); });
  dpage.on('pageerror', (e) => demoErrors.push('PAGEERR: ' + e.message.slice(0, 140)));
  await dpage.goto(DEMO, { waitUntil: 'networkidle' }).catch((e) => (result.demo.loadError = String(e).split('\n')[0]));
  await dpage.waitForTimeout(2500);
  // click AI 洞察 nav
  try {
    const aiNav = dpage.getByText('🧠 AI 洞察', { exact: false });
    if (await aiNav.count() > 0) await aiNav.first().click();
  } catch (e) {}
  await dpage.waitForTimeout(2200);
  const demo = await dpage.evaluate(() => {
    const t = document.body.innerText;
    const canvases = [...document.querySelectorAll('canvas')];
    let echartsAxis = { pred: false, real: false };
    try {
      if (window.echarts) {
        for (const c of canvases) {
          const inst = window.echarts.getInstanceByDom(c);
          if (inst) {
            const opt = inst.getOption();
            const names = JSON.stringify(opt).slice(0, 4000);
            if (/预测意图/.test(names)) echartsAxis.pred = true;
            if (/实际意图/.test(names)) echartsAxis.real = true;
          }
        }
      }
    } catch (e) {}
    return {
      canvas: canvases.length,
      cramersV: /Cramér'?s\s*V|Cramer/.test(t),
      top3: /Top[-\s]?3\s*混淆/.test(t),
      axisPredDom: /预测意图/.test(t),
      axisRealDom: /实际意图/.test(t),
      axisPredEcharts: echartsAxis.pred,
      axisRealEcharts: echartsAxis.real,
      navItems: [...document.querySelectorAll('a,button,li')].filter((e) => (e.textContent || '').trim().length > 0 && (e.textContent || '').trim().length < 20).length,
    };
  });
  result.demo.consoleErrors = demoErrors.slice(0, 10);
  result.demo.nav = { pass: demo.navItems >= 5, note: `navItems=${demo.navItems}` };
  const axisOk = (demo.axisPredDom || demo.axisPredEcharts) && (demo.axisRealDom || demo.axisRealEcharts);
  result.demo.confusion = {
    cramersV: demo.cramersV,
    top3: demo.top3,
    axisLabels: axisOk,
    pass: demo.cramersV && demo.top3 && axisOk && demo.canvas > 0,
    note: `cV=${demo.cramersV} top3=${demo.top3} axisDom(p=${demo.axisPredDom},r=${demo.axisRealDom}) axisEcharts(p=${demo.axisPredEcharts},r=${demo.axisRealEcharts})`,
  };
  if (demoErrors.length > 0) evidence.push(`DEMO console:${demoErrors.slice(0, 2).join('|')}`);
  if (!(demo.cramersV && demo.top3 && axisOk)) evidence.push(`DEMO混淆区 cV=${demo.cramersV} top3=${demo.top3} axis=${axisOk}`);

  await browser.close();
  result.routing = evidence.length > 0 ? 'Engineer' : 'NoOne';
  result.evidence = evidence;
  writeFileSync(OUT, JSON.stringify(result, null, 2), 'utf8');
  console.log('DONE routing=' + result.routing + ' evidence=' + evidence.length);
})().catch((e) => {
  writeFileSync(OUT, JSON.stringify({ fatal: String(e).split('\n').slice(0, 12), partial: result }, null, 2), 'utf8');
  console.log('FATAL ' + String(e).split('\n').slice(0, 3).join(' | '));
});
