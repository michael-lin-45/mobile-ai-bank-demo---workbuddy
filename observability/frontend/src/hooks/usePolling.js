import { useEffect, useRef, useCallback } from 'react';

/**
 * usePolling — 轮询 Hook
 *
 * 定时执行回调函数，支持开关控制、动态间隔
 *
 * @param {Function} callback       - 轮询回调函数
 * @param {number}   [intervalMs=3000] - 轮询间隔（毫秒）
 * @param {boolean}  [enabled=true]    - 是否启用轮询
 */
export default function usePolling(callback, intervalMs = 3000, enabled = true) {
  const savedCallback = useRef(callback);

  // 始终保持最新的 callback 引用
  useEffect(() => {
    savedCallback.current = callback;
  }, [callback]);

  useEffect(() => {
    if (!enabled) return;

    // 立即执行一次
    savedCallback.current();

    const timer = setInterval(() => {
      savedCallback.current();
    }, intervalMs);

    return () => clearInterval(timer);
  }, [intervalMs, enabled]);
}
