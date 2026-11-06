import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Local development: the app calls the BFF on :8080 and, for the demo toolbar only, the vendor
// simulator on :8090. In Docker, nginx does the same routing (see nginx.conf).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/v1': 'http://localhost:8080',
      '/sim': { target: 'http://localhost:8090', rewrite: (p) => p.replace(/^\/sim/, '') },
    },
  },
});
