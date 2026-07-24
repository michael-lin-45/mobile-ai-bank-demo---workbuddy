// 全局测试 setup：jest-dom 匹配器 + jsdom 缺失的浏览器 API 兜底（antd v5 / echarts 依赖）。
import '@testing-library/jest-dom';

if (typeof window !== 'undefined') {
  // antd v5 响应式断点依赖 matchMedia（jsdom 未实现）
  if (!window.matchMedia) {
    window.matchMedia = (query) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    });
  }
  // 部分图表/自适应组件依赖 ResizeObserver
  if (!window.ResizeObserver) {
    window.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }
  if (!window.scrollTo) {
    window.scrollTo = () => {};
  }
}
