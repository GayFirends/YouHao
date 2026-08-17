import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { initDatabase } from './services/database'
import './assets/main.css'

async function bootstrap() {
  await initDatabase()
  createApp(App).use(createPinia()).mount('#app')
}

bootstrap().catch((error) => {
  console.error('Application failed to start', error)
  document.querySelector<HTMLDivElement>('#app')!.textContent = '本地数据库初始化失败，请刷新后重试。'
})
