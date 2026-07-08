const { chromium } = require('@playwright/test');

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ locale: 'zh-CN' });
  
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  
  await page.goto('http://127.0.0.1:3000/session', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(3000);
  
  console.log('URL before click:', page.url());
  
  const btns = page.locator('span').filter({ hasText: '查看回放' });
  const count = await btns.count();
  console.log(`Buttons: ${count}`);
  
  if (count > 0) {
    // Try clicking with force and capture what happens
    await btns.first().click({ force: true });
    await page.waitForTimeout(2000);
    
    console.log('URL after click:', page.url());
    console.log('Body text:', (await page.locator('body').innerText().catch(() => '')).substring(0, 200));
    console.log('Page errors:', errors);
    
    // Check if modal elements exist
    const dialogs = await page.locator('[role="dialog"]').count();
    console.log('Role=dialog:', dialogs);
  }
  
  await browser.close();
})();
