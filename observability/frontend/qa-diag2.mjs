import { chromium } from 'playwright';
import { pathToFileURL } from 'node:url';
import { writeFileSync } from 'node:fs';

const BASE = 'http://localhost:3000';
const DEMO = pathToFileURL('D:/GitHub/mobile-ai-bank-demo - workbuddy/docs/specs/可观测DEMO-v24-WorkBuddy.html').href;
const out = {};

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();

  // main app: /insight tabs + confusion region
  await page.goto(BASE + '/insight', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  out.insightTabs = await page.evaluate(() =>
    [...document.querySelectorAll('[role="tab"]')].map((t) => (t.textContent || '').trim()).filter(Boolean)
  );
  const accTab = page.getByRole('tab', { name: /准确率|混淆|精度/ });
  if (await accTab.count() > 0) await accTab.first().click();
  await page.waitForTimeout(2000);
  out.acc = await page.evaluate(() => {
    const t = document.body.innerText;
    // find confusion canvas: the one near '混淆' or 'Cramér'
    const canvases = [...document.querySelectorAll('canvas')];
    let confIdx = -1;
    canvases.forEach((c, i) => {
      const ctx = c.parentElement ? c.parentElement.innerText : '';
      if (/混淆|Cramér/.test(ctx)) confIdx = i;
    });
    const conf = confIdx >= 0 ? canvases[confIdx] : canvases[0];
    const region = conf && conf.parentElement ? conf.parentElement.parentElement || conf.parentElement : null;
    return {
      bodyHasIntent: /意图/.test(t),
      intentContexts: (t.match(/.{0,8}意图.{0,8}/g) || []).slice(0, 8),
      confusionRegionText: region ? region.innerText.replace(/\s+/g, ' ').slice(0, 300) : 'none',
      canvasCount: canvases.length,
    };
  });
  // RAG search across all tabs
  out.ragSearch = await page.evaluate(() => {
    const t = document.body.innerText;
    return {
      ragTextNow: /检索质量|Top-?K|Top-?1\/Top-?3/.test(t),
      fullIntent: /意图/.test(t),
    };
  });

  // DEMO: enumerate nav, click each, check confusion content
  const dpage = await browser.newPage();
  await dpage.goto(DEMO, { waitUntil: 'networkidle' }).catch(() => {});
  await dpage.waitForTimeout(2500);
  out.demoNavLabels = await dpage.evaluate(() =>
    [...document.querySelectorAll('a,button,li,.ant-menu-item,div')]
      .map((e) => (e.textContent || '').trim())
      .filter((x) => x.length > 0 && x.length < 18)
  );
  out.demoPerNav = [];
  const navTexts = ['📊 总览大屏', '💬 会话回放', '🔗 链路追踪', '🧠 AI 洞察', '📋 日志查询', '🔔 告警规则', '⚙️ 系统设置', '准确率', '混淆', 'RAG'];
  for (const lbl of navTexts) {
    try {
      const el = dpage.getByText(lbl, { exact: false });
      if (await el.count() > 0) {
        await el.first().click();
        await dpage.waitForTimeout(1500);
        const r = await dpage.evaluate(() => {
          const t = document.body.innerText;
          return {
            cramers: /Cramér|Cramer/.test(t),
            top3: /Top[-\s]?3\s*混淆/.test(t),
            intent: /意图/.test(t),
            intentCtx: (t.match(/.{0,6}意图.{0,6}/g) || []).slice(0, 4),
          };
        });
        out.demoPerNav.push({ nav: lbl, ...r });
      }
    } catch (e) {}
  }

  await browser.close();
  writeFileSync('D:/GitHub/mobile-ai-bank-demo - workbuddy/qa-diag2.json', JSON.stringify(out, null, 2), 'utf8');
  console.log('DIAG2 DONE');
})().catch((e) => {
  writeFileSync('D:/GitHub/mobile-ai-bank-demo - workbuddy/qa-diag2.json', JSON.stringify({ fatal: String(e).split('\n').slice(0, 8) }, null, 2), 'utf8');
  console.log('DIAG2 FATAL ' + String(e).split('\n')[0]);
});
