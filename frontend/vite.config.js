import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const target = process.env.VITE_API_TARGET || 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': { target, changeOrigin: true },
      '/v3': { target, changeOrigin: true },
      '/swagger-ui': { target, changeOrigin: true }
    }
  }
})
