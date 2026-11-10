import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
  // /oauth2 and /login: the sign-in round trip with ideagen.auth=oauth.
  server: { port: 5173, proxy: { '/api': 'http://127.0.0.1:8300', '/oauth2': 'http://127.0.0.1:8300', '/login': 'http://127.0.0.1:8300' } },
})
