import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  base: './',
  server: { port: 5173 },
  build: { target: 'es2020' },
  test: {
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'json-summary'],
      include: ['src/services/**/*.ts'],
      exclude: [
        'src/services/__tests__/**',
        'src/services/app-error.ts',
        'src/services/backup.ts',
        'src/services/database-native.ts',
        'src/services/database.ts',
      ],
      thresholds: { lines: 80, functions: 80, statements: 80, branches: 80 },
    },
  },
})
