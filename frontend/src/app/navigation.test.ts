import { describe, expect, it } from "vitest";
import { parseHash } from "./navigation";

describe("parseHash", () => {
  it("restores a comparison deep link", () => {
    expect(parseHash("#/compare/12/34")).toEqual({ page: "compare", base: 12, target: 34 });
  });

  it("ignores unrelated and malformed hashes", () => {
    expect(parseHash("#/settings")).toBeNull();
    expect(parseHash("#/compare/12/nope")).toBeNull();
  });
});
