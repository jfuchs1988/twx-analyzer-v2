import { describe, expect, it } from "vitest";
import { createBackendProxy, frontendOutDir } from "./vite.shared.ts";

describe("frontend deployment settings", () => {
  it("builds into its own dist directory", () => {
    expect(frontendOutDir).toBe("dist");
  });

  it("proxies API and Quarkus routes during development and preview", () => {
    expect(createBackendProxy()).toEqual({
      "/api": "http://localhost:8080",
      "/q": "http://localhost:8080",
    });
  });

  it("allows the local preview to use a different API port", () => {
    expect(createBackendProxy("http://localhost:8081")).toEqual({
      "/api": "http://localhost:8081",
      "/q": "http://localhost:8081",
    });
  });
});
