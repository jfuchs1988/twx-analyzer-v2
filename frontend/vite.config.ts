import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "../src/main/resources/META-INF/resources",
    emptyOutDir: true,
    chunkSizeWarningLimit: 1200
  },
  server: {
    proxy: {
      "/api": "http://localhost:8080"
    }
  }
});
