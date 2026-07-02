import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Backend port is overridable so dev/e2e can run against a non-default port
// (e.g. BACKEND_PORT=18080 when something else occupies 8080).
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
