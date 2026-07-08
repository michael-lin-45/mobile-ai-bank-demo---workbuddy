/**
 * Ant Design 5 深色工业风主题 Token
 *
 * 映射自 dashboard-v7.html CSS tokens:
 *   bg-root: #080c14 → colorBgLayout
 *   bg-surface: #0f141e → colorBgContainer
 *   bg-elevated: #161c2a → colorBgElevated
 *   accent: #3b6cf6 → colorPrimary
 *   cyan: #06b6d4 → cyan (IO 面板独立色)
 *   success: #10b981, warning: #f59e0b, danger: #ef4444
 *   purple: #8b5cf6 (L1/Domain 标识)
 */

const theme = {
  token: {
    // ── 色彩 ──
    colorPrimary: '#3b6cf6',
    colorSuccess: '#10b981',
    colorWarning: '#f59e0b',
    colorError: '#ef4444',
    colorInfo: '#06b6d4',
    colorLink: '#3b6cf6',

    // ── 背景（深色工业风） ──
    colorBgLayout: '#080c14',
    colorBgContainer: '#0f141e',
    colorBgElevated: '#161c2a',
    colorBgSpotlight: '#1c2435',
    colorBgBase: '#080c14',

    // ── 文字 ──
    colorText: '#e8edf4',
    colorTextSecondary: '#8896b0',
    colorTextTertiary: '#57637c',
    colorTextQuaternary: '#3d4a62',

    // ── 边框 ──
    colorBorder: '#1e293b',
    colorBorderSecondary: '#1e293b',

    // ── 字体 ──
    fontFamily: "'DM Sans', system-ui, -apple-system, sans-serif",
    fontFamilyCode: "'JetBrains Mono', monospace",
    fontSize: 13,
    fontSizeSM: 11,
    fontSizeLG: 15,

    // ── 圆角 ──
    borderRadius: 6,
    borderRadiusLG: 10,
    borderRadiusSM: 4,

    // ── 间距 ──
    padding: 16,
    paddingLG: 24,
    paddingSM: 12,
    paddingXS: 8,
  },

  components: {
    Layout: {
      siderBg: '#0f141e',
      triggerBg: '#161c2a',
      triggerColor: '#8896b0',
    },
    Menu: {
      darkItemBg: 'transparent',
      darkItemColor: '#8896b0',
      darkItemHoverBg: '#1c2435',
      darkItemHoverColor: '#e8edf4',
      darkItemSelectedBg: 'rgba(59,108,246,0.18)',
      darkItemSelectedColor: '#3b6cf6',
      darkSubMenuItemBg: 'transparent',
    },
    Card: {
      colorBgContainer: '#0f141e',
      colorBorderSecondary: '#1e293b',
      headerBg: '#0f141e',
    },
    Table: {
      headerBg: '#0f141e',
      rowHoverBg: '#1c2435',
      borderColor: '#1e293b',
      headerColor: '#57637c',
      rowExpandedBg: '#0f141e',
    },
    Tag: {
      defaultBg: '#161c2a',
      defaultColor: '#8896b0',
    },
    Button: {
      defaultBg: '#161c2a',
      defaultBorderColor: '#1e293b',
      defaultColor: '#8896b0',
      defaultHoverBg: '#1c2435',
      defaultHoverBorderColor: '#57637c',
      defaultHoverColor: '#e8edf4',
    },
    Input: {
      colorBgContainer: '#161c2a',
      colorBorder: '#1e293b',
      hoverBorderColor: '#57637c',
      colorText: '#e8edf4',
      colorTextPlaceholder: '#57637c',
    },
    Select: {
      colorBgContainer: '#161c2a',
      colorBorder: '#1e293b',
      colorText: '#e8edf4',
    },
    Pagination: {
      colorText: '#8896b0',
      itemActiveBg: 'rgba(59,108,246,0.18)',
    },
    Spin: {
      colorBgContainer: 'transparent',
    },
    Tooltip: {
      colorBgSpotlight: '#161c2a',
    },
  },
};

export default theme;
