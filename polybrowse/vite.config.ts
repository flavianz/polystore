import { defineConfig } from 'vite'
import react, { reactCompilerPreset } from '@vitejs/plugin-react'
import babel from '@rolldown/plugin-babel'
import tailwindcss from "@tailwindcss/vite";
import path from "path"

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    babel({ presets: [reactCompilerPreset()] })
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    proxy: Object.fromEntries(
      [
        '/query',
        '/schema',
        '/collections',
        '/connections',
        '/collection',
        '/connection',
        '/document',
        '/instance',
        '/version',
      ].map((route) => [route, { target: 'http://localhost:8080', changeOrigin: true }]),
    ),
  },
})
