import { useEffect, useRef, useCallback } from 'react';
import * as echarts from 'echarts';

/**
 * useECharts — 图表统一 Hook
 *
 * 封装 echarts 实例的创建、销毁、响应式 resize
 * 返回 chartRef (绑定到 DOM 元素的 ref) 和 instanceRef (echarts 实例)
 *
 * @returns {{ chartRef: React.RefObject, instanceRef: React.RefObject }}
 */
export default function useECharts() {
  const chartRef = useRef(null);
  const instanceRef = useRef(null);

  // 初始化 echarts 实例
  const initChart = useCallback(() => {
    if (chartRef.current && !instanceRef.current) {
      instanceRef.current = echarts.init(chartRef.current);
    }
    return instanceRef.current;
  }, []);

  // 设置图表配置项
  const setOption = useCallback((option, notMerge = true) => {
    const instance = instanceRef.current;
    if (instance && option) {
      instance.setOption(option, notMerge);
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
        instanceRef.current.dispose();
        instanceRef.current = null;
      }
    };
  }, []);

  return { chartRef, instanceRef, initChart, setOption };
}
