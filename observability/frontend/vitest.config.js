import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// V23 测试设施补齐：项目此前无 jest/vitest 配置文件，本文件建立 jsdom 测试环境 + jest-dom。
// 运行入口：package.json scripts.test = "vitest run"（即 npm test）。
// 说明：Bash 环境当前故障无法实际运行，测试待环境恢复后 `npm test` 验证。
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.js'],
    css: false,
    include: [
      'src/**/*.test.{js,jsx,ts,tsx}',
      'src/**/__tests__/*.{test,spec}.{js,jsx,ts,tsx}',
    ],
  },
});
