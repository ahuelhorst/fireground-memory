import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev server forwards API calls to the Spring Boot backend, so no CORS setup is needed locally.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/v1': 'http://localhost:8080',
    },
  },
});
