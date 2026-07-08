/**
 * Ant Design 5 亮色主题令牌
 *
 * 严格参照 dashboard-v15.html CSS 变量:
 *   --primary: #1677ff
 *   --bg-layout: #f0f2f5
 *   --bg-card: #fff
 *   --bg-sidebar: #001529
 *   --text-primary: rgba(0,0,0,.88)
 *   --text-secondary: rgba(0,0,0,.65)
 *   --text-tertiary: rgba(0,0,0,.45)
 *   --border: #f0f0f0
 *   --border-strong: #d9d9d9
 *   --radius: 8px
 *   --font: system-ui stack (PingFang SC / Microsoft YaHei)
 *   --shadow-card: 0 1px 2px 0 rgba(0,0,0,.03),0 1px 6px -1px rgba(0,0,0,.02),0 2px 4px 0 rgba(0,0,0,.02)
 */
const antdTheme = {
  token: {
    colorPrimary: '#1677ff',
    colorSuccess: '#52c41a',
    colorWarning: '#faad14',
    colorError: '#ff4d4f',
    colorInfo: '#1677ff',
    colorBgLayout: '#f0f2f5',
    colorBgContainer: '#ffffff',
    colorBgElevated: '#ffffff',
    borderRadius: 8,
    fontFamily: `-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Microsoft YaHei", sans-serif`,
    fontSize: 14,
    colorText: 'rgba(0,0,0,.88)',
    colorTextSecondary: 'rgba(0,0,0,.65)',
    colorTextTertiary: 'rgba(0,0,0,.45)',
    colorBorder: '#f0f0f0',
    colorBorderSecondary: '#d9d9d9',
    boxShadow: '0 1px 2px 0 rgba(0,0,0,.03), 0 1px 6px -1px rgba(0,0,0,.02), 0 2px 4px 0 rgba(0,0,0,.02)',
  },
  components: {
    Layout: {
      siderBg: '#001529',
      bodyBg: '#f0f2f5',
      headerBg: '#ffffff',
      headerPadding: '0 24px',
      headerHeight: 56,
    },
    Menu: {
      darkItemBg: '#001529',
      darkItemSelectedBg: '#1677ff',
      darkItemColor: 'rgba(255,255,255,.65)',
      darkItemSelectedColor: '#fff',
      darkItemHoverBg: 'rgba(255,255,255,.06)',
      darkSubMenuItemBg: '#001529',
      itemBorderRadius: 0,
    },
    Card: {
      colorBgContainer: '#ffffff',
      borderRadiusLG: 8,
      paddingLG: 20,
      padding: 20,
      boxShadow: '0 1px 2px 0 rgba(0,0,0,.03), 0 1px 6px -1px rgba(0,0,0,.02), 0 2px 4px 0 rgba(0,0,0,.02)',
    },
    Table: {
      headerBg: '#fafafa',
      headerColor: 'rgba(0,0,0,.65)',
      cellPaddingBlock: 10,
      cellPaddingInline: 12,
      borderColor: '#f0f0f0',
      rowHoverBg: '#fafafa',
      fontSize: 13,
    },
    Tabs: {
      horizontalItemPadding: '8px 20px',
      inkBarColor: '#1677ff',
      itemColor: 'rgba(0,0,0,.65)',
      itemHoverColor: '#1677ff',
      itemSelectedColor: '#1677ff',
      cardGutter: 0,
      titleFontSize: 14,
    },
    Modal: {
      borderRadiusLG: 8,
      boxShadow: '0 6px 16px rgba(0,0,0,.08), 0 3px 6px rgba(0,0,0,.12)',
      headerBg: '#ffffff',
      contentBg: '#ffffff',
      titleFontSize: 16,
    },
    Button: {
      borderRadius: 6,
      primaryColor: '#1677ff',
      defaultBorderColor: '#d9d9d9',
      paddingInline: 16,
    },
    Tag: {
      borderRadiusSM: 4,
      fontSizeSM: 11,
    },
    Badge: {
      dotSize: 6,
    },
    Select: {
      optionFontSize: 13,
      borderRadius: 6,
    },
    Input: {
      borderRadius: 6,
      paddingInline: 12,
    },
    Progress: {
      borderRadius: 3,
    },
  },
};

export default antdTheme;
