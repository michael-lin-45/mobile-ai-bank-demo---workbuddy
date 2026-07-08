// Playwright 配置 — 测试结果输出到 test/test-results/
const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: '.',
  testMatch: 'test/**/*.spec.js',
  outputDir: 'test/test-results',
});
