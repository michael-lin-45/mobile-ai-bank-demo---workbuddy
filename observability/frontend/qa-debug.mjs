import { chromium } from 'playwright';

const BASE = 'http://localhost:3000';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await ctx.newPage();
await page.route('**/api/v1/metrics/realtime', (route) =>
  route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, data: {} }) }));
await page.goto(BASE, { waitUntil: 'domcontentloaded' });
await sleep(1000);

// ---- Zone E deltas ----
await page.locator('.ant-menu-item', { hasText: '总览大屏' }).first().click();
await sleep(1500);
const zoneE = await page.evaluate(() => {
  const headers = Array.from(document.querySelectorAll('div'));
  const h = headers.find((x) => (x.textContent || '').includes('知识检索（RAG）'));
  const container = h.parentElement;
  const deltas = Array.from(container.querySelectorAll('div')).filter(
    (d) => /^[↓↑→]/.test((d.textContent || '').trim()) && d.style.color,
  );
  return deltas.map((d, i) => ({ i, text: d.textContent.trim(), color: d.style.color, cls: d.className || '' }));
});
console.log('ZONE_E_DELTAS', JSON.stringify(zoneE, null, 0));

// ---- /insight diagnosis ----
await page.locator('.ant-menu-item', { hasText: 'AI 洞察' }).first().click();
await sleep(2500);
const anchors = await page.evaluate(() =>
  Array.from(document.querySelectorAll('a')).map((a) => a.textContent.trim()).filter((t) => t.includes('去')));
console.log('TOP5_ANCHORS', JSON.stringify(anchors));
const diag = await page.evaluate(() => {
  const t = document.querySelector('.ant-layout-content').innerText;
  return {
    hasSlow: t.includes('慢会话根因表'),
    hasS1001: t.includes('S-1001'),
    hasTop5Card: t.includes('优先行动建议'),
    hasAcc: t.includes('准确率风险'),
  };
});
console.log('DIAGNOSIS', JSON.stringify(diag));

// ---- external -> RAG ----
await page.locator('.ant-tabs-tab', { hasText: '外部调用' }).first().click();
await sleep(2000);
const segInfo = await page.evaluate(() => ({
  segCount: document.querySelectorAll('.ant-segmented-item').length,
  segLabels: Array.from(document.querySelectorAll('.ant-segmented-item')).map((s) => s.textContent.trim()),
}));
console.log('SEG', JSON.stringify(segInfo));
await page.locator('.ant-segmented-item', { hasText: 'RAG' }).first().click();
await sleep(2500);
const rag = await page.evaluate(() => {
  const t = document.querySelector('.ant-layout-content').innerText;
  return {
    canvas: document.querySelectorAll('.ant-layout-content canvas').length,
    hasRagTitle: t.includes('RAG 检索质量'),
    hasTopK: t.includes('Top-K 相关性分布'),
    hasDemo: t.includes('DEMO'),
    hasKpi: ['重排时延 P95', '平均召回文档数', 'RAG 触发率', '检索错误率', '重排错误率'].filter((k) => t.includes(k)),
    snippet: t.slice(0, 200),
  };
});
console.log('RAG', JSON.stringify(rag, null, 0));

await browser.close();
