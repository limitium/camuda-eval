import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  build: {
    outDir: '../backend/build/resources/static',
    emptyOutDir: true
  },
  server: {
    // Several instances (dev, stg) can each have a UI: API_TARGET=http://localhost:7071 UI_PORT=5174 npx vite
    port: Number(process.env.UI_PORT || 5173),
    proxy: {
      '/api': {
        target: process.env.API_TARGET || 'http://localhost:7070',
        changeOrigin: true,
        secure: false,
        configure: (proxy, options) => {
          proxy.on('proxyReq', (proxyReq, req, res) => {
            console.log('Proxying request:', req.method, req.url);
          });
        }
      }
    }
  }
})


