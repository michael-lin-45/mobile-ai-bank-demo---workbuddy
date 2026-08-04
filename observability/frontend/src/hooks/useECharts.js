import { useEffect, useRef, useCallback } from 'react';
import * as echarts from 'echarts';

/**
 * useECharts — 图表统一 Hook
 *
 * 封装 echarts 实例的创建、销毁、响应式 resize
 * 返回 chartRef (绑定到 DOM 元素的 ref) 和 instanceRef (echarts 实例)
 *
 * 说明：jsdom / SSR 等无头环境未实现 canvas 2D 上下文，直接 echarts.init
 * 会抛错导致组件崩溃。这里在初始化前后做能力探测与异常兜底，使图表组件
 * 在无头环境下安全降级（不渲染图形，但不报错），真实浏览器行为保持不变。
 *
 * @returns {{ chartRef: React.RefObject, instanceRef: React.RefObject }}
 */

/**
 * 探测当前环境是否支持 echarts 所需的 canvas 2D 上下文。
 * @returns {boolean}
 */
function detectCanvasSupport() {
  if (typeof document === 'undefined') return false;
  try {
    const canvas = document.createElement('canvas');
    return !!(canvas && typeof canvas.getContext === 'function' && canvas.getContext('2d'));
  } catch {
    return false;
  }
}

const CANVAS_SUPPORTED = detectCanvasSupport();

export default function useECharts() {
  const chartRef = useRef(null);
  const instanceRef = useRef(null);

  // 初始化 echarts 实例（无头环境下安全跳过）
  const initChart = useCallback(() => {
    if (!CANVAS_SUPPORTED) return null;
    if (chartRef.current && !instanceRef.current) {
      try {
        instanceRef.current = echarts.init(chartRef.current);
      } catch {
        // 极端环境下降级：避免阻断组件渲染
        instanceRef.current = null;
      }
    }
    return instanceRef.current;
  }, []);

  // 设置图表配置项
  const setOption = useCallback((option, notMerge = true) => {
    const instance = instanceRef.current;
    if (instance && option) {
      try {
        instance.setOption(option, notMerge);
      } catch {
        // 无头环境忽略渲染错误
      }
    }
  }, []);

  // 响应式 resize
  useEffect(() => {
    const handleResize = () => {
      instanceRef.current?.resize();
    };
    window.addEventListener('resize', handleResize);
    return () => {
      window.removeEventListener('resize', handleResize);
    };
  }, []);

  // 组件卸载时销毁实例
  useEffect(() => {
    return () => {
      if (instanceRef.current) {
        try {
          instanceRef.current.dispose();
        } catch {
          // 无头环境忽略
        }
        instanceRef.current = null;
      }
    };
  }, []);

  return { chartRef, instanceRef, initChart, setOption };
}
