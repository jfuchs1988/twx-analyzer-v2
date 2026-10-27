export const frontendOutDir = "dist";

export function createBackendProxy(target = "http://localhost:8080") {
  return {
    "/api": target,
    "/q": target,
  };
}
