import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const backend = process.env.API_URL || 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': { target: backend, changeOrigin: true },
      '/ingest': { target: backend, changeOrigin: true },
    },
  },
});
