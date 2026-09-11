import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // 开发环境代理后端接口，避免跨域
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      // 网页幻灯片由后端 SlideController 提供（GET /slides/{id}/page{n}.html）。
      // 不代理的话这个请求会落到 Vite 自己身上，被 SPA 兜底返回 index.html，
      // 前端 iframe 里就是一片空白 / 整个应用再套一层。
      '/slides': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
})
