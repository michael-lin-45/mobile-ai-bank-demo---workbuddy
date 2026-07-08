const { chromium } = require('@playwright/test');

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ locale: 'zh-CN' });
  
  // Test session modal
  await page.goto('http://127.0.0.1:3000/session', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(3000);
  
  const bodyLenBefore = (await page.locator('body').innerHTML()).length;
  console.log(`Body HTML before click: ${bodyLenBefore}`);
  
  // Find and click the view button
  const btns = page.locator('button, a').filter({ hasText: /查看|回放/i });
  const count = await btns.count();
  console.log(`View buttons: ${count}`);
  
  if (count > 0) {
    await btns.first().click();
    await page.waitForTimeout(2000);
    
    const bodyLenAfter = (await page.locator('body').innerHTML()).length;
    console.log(`Body HTML after click: ${bodyLenAfter}`);
    
    // Check for dialog role
    const dialogs = await page.locator('[role="dialog"]').count();
    console.log(`[role="dialog"]: ${dialogs}`);
    
    // Check for any fixed overlay
    const fixedDivs = await page.locator('div[style*="position"]').count();
    console.log(`Fixed position divs: ${fixedDivs}`);
    
    // Check page errors
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    console.log(`Page errors: ${errors.length}`);
  }
  
  await browser.close();
})();
