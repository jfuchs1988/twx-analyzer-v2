import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";
import { createBackendProxy, frontendOutDir } from "./vite.shared.ts";

const srcDirectory = fileURLToPath(new URL("./src", import.meta.url));

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "TWXAV2_");
  const proxy = createBackendProxy(env.TWXAV2_API_PROXY_TARGET);

  return {
    plugins: [react()],
    resolve: {
      alias: { "@": srcDirectory },
    },
    build: {
      outDir: frontendOutDir,
      emptyOutDir: true,
      chunkSizeWarningLimit: 1200,
    },
    server: { proxy },
    preview: { proxy },
  };
});
