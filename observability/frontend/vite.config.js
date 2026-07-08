
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 3000,
    strictPort: true,   // 端口被占用时报错，不自增到 3001/3002...
    open: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:9090',
        changeOrigin: true,
      },
      '/health': {
        target: 'http://127.0.0.1:9090',
        changeOrigin: true,
      }
    }
  }
})
