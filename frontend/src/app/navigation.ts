export type View =
  | { page: "list" }
  | { page: "toolkits" }
  | { page: "search" }
  | { page: "dashboard" }
  | { page: "compare"; base?: number | null; target?: number | null }
  | { page: "settings" }
  | { page: "rules"; snapshotId?: number }
  | { page: "detail"; id: number; from: "list" | "toolkits" | "search" };

/** Deep link (B6 follow-up): #/compare/{base}/{target} -> comparison view. */
export function parseHash(hash: string): View | null {
  const match = /^#\/compare\/(\d+)\/(\d+)$/.exec(hash);
  return match ? { page: "compare", base: Number(match[1]), target: Number(match[2]) } : null;
}
