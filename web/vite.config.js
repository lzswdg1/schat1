import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

const gatewayTarget = process.env.SCHAT_WEB_GATEWAY_URL || 'http://127.0.0.1:8080'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    proxy: {
      // WebSocket 代理（必须在 /api 之前，优先匹配）
      '/api/ws': {
        target: gatewayTarget,
        ws: true,
        changeOrigin: true
      },
      // HTTP API 代理
      '/api': {
        target: gatewayTarget,
        changeOrigin: true
      }
    }
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.{test,spec}.{js,ts}', 'tests/**/*.{test,spec,property}.{js,ts}'],
    setupFiles: ['./tests/setup.js']
  }
})
