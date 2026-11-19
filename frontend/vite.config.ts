import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  build: { emptyOutDir: true },
  server: { port: 5173, strictPort: true, proxy: { '/api': { target: process.env.VITE_API_TARGET ?? 'http://127.0.0.1:8080', changeOrigin: true, ws: true } } },
  // Bound concurrent jsdom environments on shared build hosts; CLI can override.
  test: { environment: 'jsdom', setupFiles: ['./src/test/setup.ts'], restoreMocks: true, clearMocks: true, maxWorkers: 2 },
})
