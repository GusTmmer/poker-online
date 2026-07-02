import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Backend port for the dev proxy; override with BACKEND_PORT when 8080 is taken.
const backendPort = process.env.BACKEND_PORT ?? '8080'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': { target: `http://localhost:${backendPort}`, changeOrigin: true },
      '/ws': { target: `ws://localhost:${backendPort}`, ws: true },
    },
  },
})
