import { render } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import ZoneBadge from '../dashboard/ZoneBadge';

/**
 * ZoneBadge 测试（大屏 Zone 字母徽标 A/B/C/D/E）。
 * 纯展示组件，验证字母渲染、默认配色、自定义配色与尺寸降级。
 */
describe('ZoneBadge — 字母徽标', () => {
  it('① 渲染传入字母，默认主题色 #1677ff', () => {
    const { container, getByText } = render(<ZoneBadge letter="E" />);
    expect(getByText('E')).toBeInTheDocument();
    const span = container.querySelector('span');
    expect(span.style.background).toMatch(/#1677ff|rgb\(22,\s*119,\s*255\)/);
  });

  it('② 自定义 color 透传为占位底', () => {
    const { container } = render(<ZoneBadge letter="A" color="#08979c" />);
    const span = container.querySelector('span');
    expect(span.style.background).toMatch(/#08979c|rgb\(8,\s*151,\s*156\)/);
  });

  it('③ size="lg" 高度大于 size="sm"（22 vs 18）', () => {
    const { container: sm } = render(<ZoneBadge letter="B" size="sm" />);
    const { container: lg } = render(<ZoneBadge letter="B" size="lg" />);
    const smH = parseFloat(sm.querySelector('span').style.height);
    const lgH = parseFloat(lg.querySelector('span').style.height);
    expect(lgH).toBeGreaterThan(smH);
    expect(smH).toBe(18);
    expect(lgH).toBe(22);
  });

  it('④ 文字颜色为白、胶囊圆角、字重加粗', () => {
    const { container } = render(<ZoneBadge letter="C" />);
    const span = container.querySelector('span');
    expect(span.style.color).toMatch(/#fff|rgb\(255,\s*255,\s*255\)/);
    expect(span.style.borderRadius).toBe('999px');
    expect(span.style.fontWeight).toBe('700');
  });
});
