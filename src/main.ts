import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { Capacitor } from '@capacitor/core'
import App from './App.vue'
import { initDatabase } from './services/database'
import './assets/main.css'

async function configureNativeUi() {
  if (!Capacitor.isNativePlatform()) return
  const { StatusBar, Style } = await import('@capacitor/status-bar')
  await StatusBar.setOverlaysWebView({ overlay: false })
  await StatusBar.setStyle({ style: Style.Light })
  await StatusBar.setBackgroundColor({ color: '#ffffff' })
}

async function bootstrap() {
  await initDatabase()
  await configureNativeUi()
  createApp(App).use(createPinia()).mount('#app')
}

bootstrap().catch((error) => {
  console.error('Application failed to start', error)
  document.querySelector<HTMLDivElement>('#app')!.textContent = '本地数据库初始化失败，请刷新后重试。'
})
