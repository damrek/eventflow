import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

/**
 * The SPA always calls its own origin. In development and in `vite preview` the three service
 * prefixes are proxied to the running Spring Boot services, which keeps the browser free of CORS
 * configuration and identical in both modes.
 */
const proxy = {
  "/api/catalog": {
    target: "http://localhost:8081",
    changeOrigin: true,
    rewrite: (path: string) => path.replace(/^\/api\/catalog/, "/api"),
  },
  "/api/booking": {
    target: "http://localhost:8082",
    changeOrigin: true,
    rewrite: (path: string) => path.replace(/^\/api\/booking/, "/api"),
  },
  "/api/notification": {
    target: "http://localhost:8083",
    changeOrigin: true,
    rewrite: (path: string) => path.replace(/^\/api\/notification/, "/api"),
  },
};

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy,
  },
  preview: {
    port: 4173,
    proxy,
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: "./src/test/setup.ts",
    css: false,
  },
});
