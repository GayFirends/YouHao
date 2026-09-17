import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'com.youhao.fueltrack',
  appName: '油迹',
  webDir: 'dist',
  android: {
    allowMixedContent: false,
    adjustMarginsForEdgeToEdge: 'force',
  },
  plugins: {
    StatusBar: {
      overlaysWebView: false,
      style: 'DARK',
      backgroundColor: '#EDF1EB',
    },
  },
}

export default config
