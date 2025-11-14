import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react-swc'
import tailwindcss from '@tailwindcss/vite'
import basicSsl from '@vitejs/plugin-basic-ssl'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],  // Removed basicSsl
  server: {
    host: '0.0.0.0', // Allow network access
    port: 5173
    // https: false  // Disabled for local development (backend is HTTP)
    // Note: For cross-device voice testing, enable HTTPS and configure backend for HTTPS too
  }
})
