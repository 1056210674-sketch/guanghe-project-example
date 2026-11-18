import type { ThemeConfig } from 'antd'

export const appTheme: ThemeConfig = {
  token: {
    colorPrimary: '#E53935',
    colorPrimaryHover: '#F04A46',
    colorPrimaryActive: '#C62828',
    colorInfo: '#1677FF',
    colorSuccess: '#22C55E',
    colorWarning: '#FA8C16',
    colorError: '#F53F3F',
    colorText: '#1A1A1A',
    colorTextSecondary: '#666666',
    colorTextTertiary: '#999999',
    colorBorder: '#E5E7EB',
    colorBgLayout: '#F7F8FA',
    colorBgContainer: '#FFFFFF',
    borderRadius: 8,
    borderRadiusLG: 12,
    fontFamily: 'PingFang SC, Microsoft YaHei, sans-serif',
    controlHeight: 40,
    motionDurationFast: '0.12s',
    motionDurationMid: '0.2s',
    motionDurationSlow: '0.32s',
  },
  components: {
    Button: {
      borderRadius: 6,
      primaryShadow: 'none',
    },
    Card: {
      borderRadiusLG: 12,
      boxShadowTertiary: '0 1px 2px rgba(0, 0, 0, 0.06)',
    },
    Input: {
      activeBorderColor: '#E53935',
      hoverBorderColor: '#F04A46',
    },
    Layout: {
      bodyBg: '#F7F8FA',
      headerBg: '#FFFFFF',
      siderBg: '#FFFFFF',
    },
    Menu: {
      itemActiveBg: '#FFF1F0',
      itemSelectedBg: '#FFF1F0',
      itemSelectedColor: '#E53935',
      itemBorderRadius: 6,
    },
  },
}
