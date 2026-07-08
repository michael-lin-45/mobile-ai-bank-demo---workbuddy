const { chromium } = require('@playwright/test');

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ locale: 'zh-CN' });
  
  const consoleLogs = [];
  page.on('console', msg => consoleLogs.push(`[${msg.type()}] ${msg.text()}`));
  page.on('pageerror', err => consoleLogs.push(`[PAGE_ERROR] ${err.message}`));
  
  // Test 1: AI Insights page
  console.log('\n=== AI Insights (/insight) ===');
  await page.goto('http://127.0.0.1:3000/insight', { waitUntil: 'domcontentloaded', timeout: 15000 });
  await page.waitForTimeout(3000);
  
  const bodyHTML = await page.locator('body').innerHTML().catch(() => '(error)');
  console.log(`Body HTML length: ${bodyHTML.length}`);
  console.log(`Body text: ${(await page.locator('body').innerText().catch(() => '')).substring(0, 200)}`);
  
  // Check React root
  const rootHTML = await page.locator('#root').innerHTML().catch(() => '(error)');
  console.log(`#root HTML length: ${rootHTML.length}`);
  console.log(`#root HTML preview: ${rootHTML.substring(0, 300)}`);
  
  // Test 2: Session page - check modal
  console.log('\n=== Session (/session) ===');
  await page.goto('http://127.0.0.1:3000/session', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2000);
  
  const viewBtns = await page.locator('button, a').filter({ hasText: /查看|详情|view/i }).count();
  console.log(`View/Detail buttons: ${viewBtns}`);
  
  if (viewBtns > 0) {
    await page.locator('button, a').filter({ hasText: /查看|详情|view/i }).first().click();
    await page.waitForTimeout(1500);
    
    const modals = await page.locator('.ant-modal, [role="dialog"], .ant-drawer').count();
    console.log(`Modals after click: ${modals}`);
    
    // Check if anything changed in DOM
    const bodyLen = (await page.locator('body').innerHTML()).length;
    console.log(`Body HTML after click: ${bodyLen}`);
  }
  
  // Test 3: Trace page  
  console.log('\n=== Trace (/trace) ===');
  await page.goto('http://127.0.0.1:3000/trace', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2000);
  
  const traceLinks = await page.locator('a, button, td').filter({ hasText: /^[a-f0-9]{8,}/i }).count();
  console.log(`Trace ID links: ${traceLinks}`);
  
  if (traceLinks > 0) {
    await page.locator('a, button, td').filter({ hasText: /^[a-f0-9]{8,}/i }).first().click();
    await page.waitForTimeout(1500);
    const modals = await page.locator('.ant-modal, [role="dialog"], .ant-drawer').count();
    console.log(`Modals after trace click: ${modals}`);
  }
  
  // Print all console errors
  console.log('\n=== CONSOLE LOGS ===');
  const errors = consoleLogs.filter(l => l.includes('error') || l.includes('Error') || l.includes('ERROR') || l.includes('PAGE_ERROR'));
  console.log(`Total logs: ${consoleLogs.length}, Errors: ${errors.length}`);
  errors.forEach(e => console.log(`  ${e}`));
  
  await browser.close();
})();
