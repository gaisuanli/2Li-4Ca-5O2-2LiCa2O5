import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const backendPort = Number.parseInt(process.env.SERVER_PORT || '8080', 10)
const backendHttpTarget = `http://127.0.0.1:${backendPort}`
const backendWsTarget = `ws://127.0.0.1:${backendPort}`

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: backendHttpTarget, changeOrigin: true },
      '/ws': { target: backendWsTarget, ws: true }
    }
  },
  build: {
    target: 'es2022',
    sourcemap: false,
    chunkSizeWarningLimit: 1400
  }
})
