import '@testing-library/jest-dom';

// jsdom 不实现 matchMedia，而 antd 的 responsiveObserver / useBreakpoint 会调用它。
// 提供一个最小可用的 matchMedia 桩，避免 antd 组件在 jsdom 下抛错。
if (typeof window !== 'undefined' && !window.matchMedia) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    value: (query) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  });
}
