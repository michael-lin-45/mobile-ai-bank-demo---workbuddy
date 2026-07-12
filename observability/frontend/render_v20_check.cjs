const { chromium } = require('playwright');
const path = require('path');

const fileUrl = 'file:///' + path.resolve(process.argv[2]);
const outDir = process.argv[3] || 'test/v20-shots';

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE: ' + m.text()); });
  page.on('pageerror', e => errors.push('PAGEERR: ' + e.message));

  await page.goto(fileUrl, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);

  require('fs').mkdirSync(outDir, { recursive: true });

  const navCount = await page.locator('.nav-item').count();
  console.log('nav items:', navCount);
  for (let i = 0; i < navCount; i++) {
    const nav = page.locator('.nav-item').nth(i);
    const label = (await nav.innerText()).replace(/\n/g, ' ').trim();
    await nav.click();
    await page.waitForTimeout(900);
    const pid = await page.evaluate(() => {
      const a = document.querySelector('.page.active');
      return a ? a.id : 'none';
    });
    await page.screenshot({ path: path.join(outDir, `nav-${i}-${pid}-${label.replace(/[^\w]/g,'_')}-clean.png`), fullPage: true });
    const canvasInfo = await page.evaluate(() => {
      const cs = Array.from(document.querySelectorAll('.page.active canvas'));
      const rendered = cs.filter(c => c.width > 0 && c.height > 0).length;
      return { total: cs.length, rendered };
    });
    console.log(`NAV[${i}] ${label} -> page=${pid} canvases=${canvasInfo.total} rendered=${canvasInfo.rendered}`);
  }

  // AI洞察 main tabs only (skip nested perf sub-tabs)
  await page.evaluate(() => {
    const items = Array.from(document.querySelectorAll('.nav-item'));
    const t = items.find(n => n.textContent.includes('AI 洞察'));
    if (t) t.click();
  });
  await page.waitForTimeout(800);
  const tabCount = await page.evaluate(() =>
    Array.from(document.querySelectorAll('#p-insight .tab')).filter(t => {
      const o = t.getAttribute('onclick');
      return o && o.includes('itab(');
    }).length
  );
  console.log('insight main tabs:', tabCount);
  for (let i = 0; i < tabCount; i++) {
    await page.evaluate(idx => {
      const t = Array.from(document.querySelectorAll('#p-insight .tab')).filter(t => {
        const o = t.getAttribute('onclick');
        return o && o.includes('itab(');
      })[idx];
      if (t) t.click();
    }, i);
    await page.waitForTimeout(700);
    const tname = await page.evaluate(i => {
      const t = Array.from(document.querySelectorAll('#p-insight .tab')).filter(t => {
        const o = t.getAttribute('onclick');
        return o && o.includes('itab(');
      })[i];
      return t ? t.textContent.trim() : 'unknown';
    }, i);
    await page.screenshot({ path: path.join(outDir, `insight-tab-${i}-${tname.replace(/[^\w]/g,'_')}-clean.png`), fullPage: true });
    const c = await page.evaluate(() => {
      const cs = Array.from(document.querySelectorAll('#p-insight canvas'));
      return { total: cs.length, rendered: cs.filter(x => x.width > 0 && x.height > 0).length };
    });
    console.log(`  INSIGHT TAB[${i}] ${tname} canvases=${c.total} rendered=${c.rendered}`);
  }

  console.log('--- ERRORS ---');
  console.log(errors.length ? errors.join('\n') : 'none');
  await browser.close();
})().catch(e => { console.error('FATAL', e); process.exit(1); });
