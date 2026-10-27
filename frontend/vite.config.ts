import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  // 다른 프로젝트(기본 5173)와 브라우저 캐시/파비콘/서비스워커가 섞이지 않도록 전용 포트를 쓴다
  server: { port: 5180, strictPort: true },
  build: { chunkSizeWarningLimit: 1500 },
})
