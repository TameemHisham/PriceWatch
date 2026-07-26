import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      // Any request starting /api is forwarded to the Spring backend.
      // Browser only ever talks to :5173, so it's same-origin — no CORS.
      '/api': 'http://localhost:8080',
    },
  },
})
