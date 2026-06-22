import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
  ],
  server: {
    port: 5173,
    proxy: {
      '/auth': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        configure: (proxy, _options) => {
          proxy.on('proxyReq', (proxyReq, _req, _res) => {
            proxyReq.setHeader('Origin', 'http://localhost:5173');
          });
        },
      },
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        configure: (proxy, _options) => {
          proxy.on('proxyReq', (proxyReq, _req, _res) => {
            proxyReq.setHeader('Origin', 'http://localhost:5173');
          });
        },
      },
      '/files': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
