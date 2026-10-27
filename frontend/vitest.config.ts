import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";
import { createBackendProxy } from "./vite.shared.ts";

const srcDirectory = fileURLToPath(new URL("./src", import.meta.url));

export default defineConfig({
  resolve: { alias: { "@": srcDirectory } },
  server: { proxy: createBackendProxy() },
  preview: { proxy: createBackendProxy() },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    clearMocks: true,
    restoreMocks: true,
  },
});
