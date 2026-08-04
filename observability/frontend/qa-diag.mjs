import { chromium } from 'playwright';
import { pathToFileURL } from 'node:url';
import { writeFileSync } from 'node:fs';

const BASE = 'http://localhost:3000';
const DEMO = pathToFileURL('D:/GitHub/mobile-ai-bank-demo - workbuddy/docs/specs/可观测DEMO-v24-WorkBuddy.html').href;
const out = {};

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();

  // real nav links on dashboard
  await page.goto(BASE + '/dashboard', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  out.dashboardNavHrefs = await page.evaluate(() =>
    [...document.querySelectorAll('a[href]')].map((a) => a.getAttribute('href')).filter(Boolean).slice(0, 30)
  );

  // /accuracy page
  await page.goto(BASE + '/accuracy', { waitUntil: 'networkidle' });
  await page.waitForTimeout(2500);
  out.accuracy = await page.evaluate(() => ({
    title: document.title,
    url: location.href,
    canvas: document.querySelectorAll('canvas').length,
    snippet: (document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 400),
    hasCramers: document.body.innerText.includes("Cramér's V") || document.body.innerText.includes('Cramer'),
    hasTop3: /Top[-\s]?3\s*混淆/.test(document.body.innerText),
    hasAxis: document.body.innerText.includes('预测意图') || document.body.innerText.includes('实际意图'),
    hasRag: /Top-?K|检索质量|Top-?1\/Top-?3/.test(document.body.innerText),
  }));

  // also try /insight?tab=accuracy and /dashboard accuracy tab
  out.insightDeepLinks = await (async () => {
    await page.goto(BASE + '/insight', { waitUntil: 'networkidle' });
    await page.waitForTimeout(1800);
    return page.evaluate(() => {
      const top5 = [...document.querySelectorAll('*')].find((e) => /优先行动|TOP\s*5|TOP5/.test(e.textContent || ''));
      const scope = top5 || document.body;
      const els = [...scope.querySelectorAll('a,button,span,div')].filter((e) => {
        const t = e.textContent || '';
        return t.includes('去') || t.includes('→');
      });
      return els.slice(0, 12).map((e) => ({
        tag: e.tagName,
        href: e.getAttribute ? e.getAttribute('href') : '',
        text: (e.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 30),
        cls: (e.className || '').toString().slice(0, 40),
      }));
    });
  })();

  // DEMO
  const dpage = await browser.newPage();
  await dpage.goto(DEMO, { waitUntil: 'networkidle' }).catch((e) => (out.demoError = String(e).split('\n')[0]));
  await dpage.waitForTimeout(2500);
  out.demo = await dpage.evaluate(() => ({
    title: document.title,
    url: location.href,
    canvas: document.querySelectorAll('canvas').length,
    snippet: (document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 500),
    navTexts: [...document.querySelectorAll('a,button,li,div')]
      .map((e) => (e.textContent || '').trim())
      .filter((t) => t.length > 0 && t.length < 20)
      .slice(0, 40),
    hasCramers: document.body.innerText.includes("Cramér's V") || document.body.innerText.includes('Cramer'),
    hasTop3: /Top[-\s]?3\s*混淆/.test(document.body.innerText),
    hasAxis: document.body.innerText.includes('预测意图') || document.body.innerText.includes('实际意图'),
  }));

  await browser.close();
  writeFileSync('D:/GitHub/mobile-ai-bank-demo - workbuddy/qa-diag.json', JSON.stringify(out, null, 2), 'utf8');
  console.log('DIAG DONE');
})().catch((e) => {
  writeFileSync('D:/GitHub/mobile-ai-bank-demo - workbuddy/qa-diag.json', JSON.stringify({ fatal: String(e).split('\n').slice(0, 8) }, null, 2), 'utf8');
  console.log('DIAG FATAL ' + String(e).split('\n')[0]);
});
