import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'com.youhao.fueltrack',
  appName: '油迹',
  webDir: 'dist',
  android: { allowMixedContent: false },
  plugins: {
    StatusBar: {
      overlaysWebView: false,
      style: 'LIGHT',
      backgroundColor: '#ffffff',
    },
  },
}

export default config
