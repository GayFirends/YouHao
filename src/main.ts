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
  await StatusBar.setStyle({ style: Style.Dark })
  await StatusBar.setBackgroundColor({ color: '#EDF1EB' })
}

async function bootstrap() {
  await initDatabase()
  await configureNativeUi()
  createApp(App).use(createPinia()).mount('#app')
}

bootstrap().catch(async (error) => {
  console.error('Application failed to start', error)
  const root = document.querySelector<HTMLDivElement>('#app')!
  root.innerHTML = ''
  const panel = document.createElement('main')
  panel.className = 'startup-recovery'
  const heading = document.createElement('h1')
  heading.textContent = '本地数据库初始化失败'
  const detail = document.createElement('p')
  detail.textContent = error instanceof Error ? error.message : '请保留现有数据并从备份恢复。'
  const reload = document.createElement('button')
  reload.className = 'button primary'
  reload.textContent = '重新加载'
  reload.addEventListener('click', () => window.location.reload())
  panel.append(heading, detail, reload)

  if (!Capacitor.isNativePlatform()) {
    try {
      const { createDatabaseStorage } = await import('./services/database-storage')
      const storage = createDatabaseStorage()
      const snapshot = await storage.read()
      if (snapshot.bytes) {
        const download = document.createElement('button')
        download.className = 'button secondary'
        download.textContent = '导出只读 SQLite 救援文件'
        download.addEventListener('click', () => {
          const url = URL.createObjectURL(new Blob([snapshot.bytes!], { type: 'application/vnd.sqlite3' }))
          const link = document.createElement('a')
          link.href = url
          link.download = `fuel-track-recovery-${new Date().toISOString().slice(0, 10)}.sqlite`
          link.click()
          window.setTimeout(() => URL.revokeObjectURL(url), 1000)
        })
        panel.append(download)
      }
      const snapshots = await storage.listSnapshots()
      const latest = snapshots.at(-1)
      if (latest) {
        const restore = document.createElement('button')
        restore.className = 'button secondary'
        restore.textContent = '恢复升级前安全快照'
        restore.addEventListener('click', async () => {
          const bytes = await storage.readSnapshot(latest)
          const current = await storage.read()
          await storage.write(bytes, current.revision)
          window.location.reload()
        })
        panel.append(restore)
      }
    } catch (recoveryError) {
      console.error('Unable to prepare recovery actions', recoveryError)
    }
  }
  root.append(panel)
})

if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => {
    void navigator.serviceWorker.register('./sw.js')
  })
}
